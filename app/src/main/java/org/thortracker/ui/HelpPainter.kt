package org.thortracker.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex1
import org.thortracker.editor.ViewController
import org.thortracker.editor.bank
import org.thortracker.editor.channelCount
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Songs

/** Reference pages: controls, the backend's effect list, and the live export check of the song. */
internal class HelpPainter(v: TrackerView) : Painter(v) {
    private val tabs = ArrayList<Pair<RectF, Int>>()
    private var bodyTop = 0f
    private var lineH = 0f
    private var touchY = 0f
    private var touchScroll = 0
    private var dragging = false
    private var tabHit = -1

    private sealed interface Line {
        data class Heading(val text: String) : Line
        data class Row(val key: String, val text: String, val color: Int = Palette.DIM) : Line
        data class Text(val text: String, val color: Int = Palette.DIM) : Line
    }

    override fun draw(c: Canvas, area: RectF) {
        val top = header(c, area, "Help", ViewController.HELP_PAGES[st.helpPage])
        tabs.clear()
        val tabH = ink.unit * 1.9f
        var x = area.left + ink.unit * 0.7f
        for ((i, name) in ViewController.HELP_PAGES.withIndex()) {
            val text = if (i == 2) "$name (${v.problems().size})" else name
            val w = ink.labelWidth(text) + ink.unit * 1.4f
            val r = RectF(x, top, x + w, top + tabH)
            val on = i == st.helpPage
            if (on) ink.rect(c, r.left, r.bottom - ink.dp(3f), r.right, r.bottom, Palette.PLAYHEAD)
            ink.label(c, text, r.centerX(), r.top, r.height(), if (on) Palette.TEXT else Palette.DIM, on, align = Paint.Align.CENTER)
            tabs += r to i
            x += w
        }
        ink.line(c, area.left, top + tabH, area.right, top + tabH, Palette.RULE, ink.dp(1f))
        bodyTop = top + tabH + ink.unit * 0.4f
        lineH = ink.unit * 1.35f
        val keyW = ink.unit * 7f
        val left = area.left + ink.unit * 0.9f
        val width = area.width() - ink.unit * 1.8f
        val lines = pageLines(width, keyW)
        val visible = maxOf(1, ((area.bottom - bodyTop) / lineH).toInt())
        st.scroll = st.scroll.coerceIn(0, maxOf(0, lines.size - visible))
        for (i in 0 until visible) {
            val line = lines.getOrNull(st.scroll + i) ?: break
            val y = bodyTop + i * lineH
            when (line) {
                is Line.Heading -> ink.label(c, line.text, left, y, lineH, Palette.TEXT, true, ink.unit * 1.05f)
                is Line.Row -> {
                    ink.mono(c, line.key, left, y, lineH, Palette.PLAYHEAD, true, ink.unit * 0.9f)
                    ink.label(c, ink.fit(line.text, width - keyW), left + keyW, y, lineH, line.color)
                }
                is Line.Text -> ink.label(c, line.text, left, y, lineH, line.color)
            }
        }
        if (lines.size > visible) {
            val trackH = area.bottom - bodyTop
            val thumbH = maxOf(ink.unit, trackH * visible / lines.size)
            val thumbY = bodyTop + (trackH - thumbH) * st.scroll / maxOf(1, lines.size - visible)
            ink.rect(c, area.right - ink.dp(4f), thumbY, area.right - ink.dp(1f), thumbY + thumbH, Palette.FAINT)
        }
    }

    private var cachedSong: org.thortracker.model.Song? = null
    private var cachedKeymap: Keymap? = null
    private var cachedPage = -1
    private var cachedWidth = -1f
    private var cachedLines: List<Line> = emptyList()

    /** Wrapped lines for the current page; rebuilt only when the song, keymap, page or width changes. */
    private fun pageLines(width: Float, keyW: Float): List<Line> {
        val keymap = v.keymap
        if (song !== cachedSong || keymap !== cachedKeymap || st.helpPage != cachedPage || width != cachedWidth) {
            val raw = when (st.helpPage) {
                0 -> controls(keymap)
                1 -> effects()
                else -> check()
            }
            cachedLines = raw.flatMap { wrapLine(it, width, keyW) }
            cachedSong = song
            cachedKeymap = keymap
            cachedPage = st.helpPage
            cachedWidth = width
        }
        return cachedLines
    }

    /** Splits a line into display lines no wider than [width]; row continuations keep the key column. */
    private fun wrapLine(line: Line, width: Float, keyW: Float): List<Line> = when (line) {
        is Line.Heading -> listOf(line)
        is Line.Row -> wrap(line.text, width - keyW).mapIndexed { i, t -> Line.Row(if (i == 0) line.key else "", t, line.color) }
        is Line.Text -> wrap(line.text, width).map { Line.Text(it, line.color) }
    }

    private fun wrap(text: String, width: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && ink.labelWidth(next) > width) { out += cur; cur = word } else cur = next
        }
        if (cur.isNotEmpty() || out.isEmpty()) out += cur
        return out
    }

    /** Several actions in one key column: "Sel+X A B" when they share a modifier, else their labels. */
    private fun Keymap.group(vararg actions: Action): String {
        val bindings = actions.map { a -> control(a)?.let { binding(it) } }
        val modifier = bindings.firstOrNull()?.modifier
        if (modifier == null || bindings.any { it == null || it.modifier != modifier }) return actions.joinToString(" ") { label(it) }
        return Keymap.buttonName(modifier) + "+" + bindings.joinToString(" ") { Keymap.buttonName(it!!.button) }
    }

    private fun controls(k: Keymap): List<Line> {
        val edit = k.label(Action.EDIT)
        val clear = k.label(Action.CLEAR)
        val listen = k.label(Action.AUDITION)
        val mark = k.label(Action.MARK)
        val dpad = k.dpad()
        val lr = k.pair(Action.LEFT, Action.RIGHT)
        val ud = k.pair(Action.UP, Action.DOWN)
        val channels = k.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        val editLr = k.held(Action.EDIT, lr)
        return listOf(
            Line.Heading("Everywhere"),
            Line.Row(dpad, "Move the cursor"),
            Line.Row("$edit hold+$dpad", "Change the value under the cursor ($lr fine, $ud coarse); one undo step"),
            Line.Row("$edit tap", "Write the last value / press the button under the cursor"),
            Line.Row("$clear tap", "Clear the value under the cursor"),
            Line.Row("$clear hold+$dpad", "Jump in larger steps"),
            Line.Row("$listen hold", "Listen without writing anything"),
            Line.Row(channels, "Previous/next channel, bank, wave or page"),
            Line.Row(k.pair(Action.UNDO, Action.REDO), "Undo / redo (whole document, both displays)"),
            Line.Row(Keymap.MODIFIERS.joinToString(" ") { Keymap.buttonName(it) }, "Alone: only start chords, so a brushed trigger never edits"),
            Line.Row(k.label(Control.SWAP), "Swap the complete views of the two displays"),
            Line.Row(k.group(Action.COPY, Action.PASTE, Action.CUT), "Copy / paste / cut"),
            Line.Row(k.label(Action.DUPLICATE), "Duplicate"),
            Line.Row(k.group(Action.OCTAVE_DOWN, Action.OCTAVE_UP), "Octave down / up (Pattern entry, or the listening note)"),
            Line.Row("${k.label(Control.LISTEN_OCTAVE_DOWN)} ${k.label(Control.LISTEN_OCTAVE_UP)}",
                "Instrument, Wave, Keyboard, Sketch: listening octave down / up; a held $listen restarts at the new octave"),
            Line.Text("Chords can mean different things per view; holding a modifier shows only what it does here."),
            Line.Row(k.label(Control.PLAY_PATTERN), "Play from the start of the current pattern, or stop"),
            Line.Row(k.label(Control.PLAY_CURSOR), "Play from the cursor row, or stop"),
            Line.Row(k.label(Control.TOOLBAR), "Show the toolbar and move the controller to it"),
            Line.Row(k.label(Control.FOCUS), "Move controller focus to the other display"),
            Line.Row("Menu", "Controls: rebind any button or chord; hints and this page follow"),
            Line.Heading("Pattern"),
            Line.Row("Columns", "Note, sound number, effect type and effect value per channel"),
            Line.Row("Sound ··", "A note without a sound number is legato: it changes pitch without restarting the sound"),
            Line.Row("$edit on note", "Empty row: write the last note and advance by the step"),
            Line.Row("$clear on note", "Clears a note; on an empty row places OFF"),
            Line.Row("OFF", "Silences the channel at once (exported as E00). GB sounds have no release phase: for a tail, " +
                "give the sound a falling volume envelope, or end it later in the row with Exx"),
            Line.Row("$editLr effect", "· is no effect, then 0 arpeggio, 1 slide up and on. 0 waits as 000 until you set its value"),
            Line.Row("$mark hold+$dpad", "Select a block for copy, cut, clear or duplicate"),
            Line.Row(k.pair(Action.INSERT_ROW, Action.DELETE_ROW), "Insert / delete a row in the channel or selection"),
            Line.Row(k.label(Action.SCALE), "Pattern panel: root, scale and length. A scale (major/minor and modes, pentatonics, blues, whole tone, exotic scales; Chromatic is all notes) makes " +
                "${k.held(Action.EDIT, dpad)} on Pulse and Wave notes land only on its notes (${k.held(Action.EDIT, ud)} an octave, " +
                "or the next scale note past it). Noise keeps every pitch. Setting a scale never changes written notes; it is not exported or heard. " +
                "Its Notes row fits the played Pulse/Wave notes to the nearest scale note (ties go down), as one undo step"),
            Line.Row("Length", "1 to 64 rows, $lr ±1, $channels ±8. Every order using the pattern plays that many rows, then moves on. " +
                "Later rows are hidden, not deleted. Combine lengths (e.g. 48, then 20) for odd phrase lengths and polyrhythms"),
            Line.Text("Rails beside a channel show how long each note keeps sounding."),
            Line.Heading("Song"),
            Line.Row(editLr, "Change the pattern an order plays, tempo, loop or a letter"),
            Line.Row(lr, "Order commands: repeat, new, clone, delete, move, loop here"),
            Line.Row(listen, "Play from this order, or stop if playing"),
            Line.Heading("Sounds"),
            Line.Row(lr, "Choose the sound slot; $channels change bank"),
            Line.Row(editLr, "Adjust the setting; $edit tap toggles on/off settings"),
            Line.Heading("Waves"),
            Line.Row(dpad, "Move the pen across samples and levels; ${k.label(Action.UP)} at the top reaches the tools"),
            Line.Row("$edit hold", "Draw at the pen height while moving"),
            Line.Heading("Keyboard"),
            Line.Row(mark, "Switch between Listen and Write"),
            Line.Row(edit, "Listen: play.  Write: write at the pattern cursor and advance"),
            Line.Row(clear, "Write mode: place OFF and advance"),
            Line.Row(k.label(Action.DOWN), "Write mode: skip a step"),
            Line.Row("Dark keys", "Above ${Songs.noteName(Songs.NOTE_MAX)}, the highest note: they neither play nor write"),
            Line.Heading("Sketch"),
            Line.Text("Tonal playground. Root/scale experiments stay local to this display; chords, notes and the circle only sound. Only the phrase's Insert writes notes (one undo step)."),
            Line.Row("$ud / $lr", "Settings, chords, notes or phrase / choose within the row"),
            Line.Row("$edit or $listen hold", "Listen to a note, a repeating chord arpeggio or the looping phrase; release to stop"),
            Line.Row(k.held(Action.EDIT, dpad), "On settings: change root, scale, tonal channel, sound, octave or Chords (triads / circle of fifths)"),
            Line.Row(mark, "Use the pattern key again; local key settings never alter the pattern"),
            Line.Row(channels, "Pulse 1, Pulse 2 or Wave; Noise has no tonal chord reference"),
            Line.Row("Circle", "$lr moves by fifths; $clear switches major / relative minor; hold $edit or $listen for the tonic triad; tap $edit makes it Sketch's key"),
            Line.Row("Phrase", "$lr chooses a control; $editLr changes length, rhythm, motion or chord tones (a new phrase from the same seed); tap $edit for Another, Vary, the Keep toggles, From copied or Insert"),
            Line.Row("Vary", "Keep rhythm: new pitches on the same rows. Keep pitches: the same notes on new rows. Neither: one to three notes move a scale step, maybe one shifts a row"),
            Line.Row("Insert", "Writes the phrase at the pattern cursor on Sketch's channel with Sketch's sound, then moves the cursor past it. Rows without a new note are cleared but keep their effects"),
            Line.Text("Seven-note scales show their stacked-third triads (irregular stacks such as double harmonic's V are left out). Other scales show only triads made entirely of their notes. " +
                "Chromatic offers all twelve notes; select a scale for chords. Degree labels use major-scale intervals (b3, #4, b6, b7)."),
            Line.Text("Preview borrows the chosen channel while the other song channels continue. Arpeggios repeat every 180 ms; the phrase loops one row per step at the song's ticks per row."),
            Line.Text("Sketch stays a 12-note reference in tuned songs; Insert moves each note to the nearest tuned pitch and skips notes whose bend needs an occupied effect slot."),
        )
    }

    private fun effects(): List<Line> {
        val out = ArrayList<Line>()
        out += Line.Text("One effect per channel per row. Values are hexadecimal. 000 is no effect.")
        out += Line.Text("Effects marked unavailable cannot be exported or previewed and are refused.")
        out += Line.Text("There is no stutter or retrigger effect in the supported hUGE set. For a stutter, repeat the note " +
            "on consecutive rows with its sound number (each restarts the sound); Exx shortens each hit and 7xx delays one.")
        val names = Songs.channelNames(song)
        val usable = names.indices.map { ch -> Songs.effectsFor(song, ch).map { it.code }.toSet() }
        for (e in Songs.effects(song)) {
            val layout = e.param.substringBefore(':').trim()
            val meaning = e.param.substringAfter(':', "").trim()
            val missing = names.indices.filter { e.code !in usable[it] }.map { names[it] }
            val title = when {
                !e.supported -> "${e.name}: unavailable"
                missing.isNotEmpty() -> "${e.name}, not on ${missing.joinToString(", ")}"
                else -> e.name
            }
            out += Line.Row("${hex1(e.code)}$layout", if (meaning.isEmpty()) title else "$title ($meaning)", if (e.supported) Palette.TEXT else Palette.FAINT)
            if (e.description.isNotBlank()) out += Line.Row("", e.description, if (e.supported) Palette.DIM else Palette.FAINT)
        }
        return out
    }

    private fun check(): List<Line> {
        val s = song
        val out = ArrayList<Line>()
        val problems = v.problems()
        out += if (problems.isEmpty()) Line.Heading("No problems: the song can be exported") else Line.Heading("Fix these before exporting")
        for (p in problems) out += Line.Text(p, Palette.ERROR)
        val warnings = v.warnings()
        if (warnings.isNotEmpty()) {
            out += Line.Heading("Worth checking")
            for (w in warnings) out += Line.Text(w, Palette.PLAYHEAD)
        }
        out += Line.Heading("Limits used")
        out += Line.Row("Orders", "${s.orders.size} of ${EditorSession.MAX_ORDERS}")
        out += Line.Row("Patterns", "${s.patterns.size} of ${EditorSession.MAX_PATTERNS} (${s.orders.toSet().size} in the arrangement)")
        for (k in InstrumentKind.entries) {
            val used = usedSlots(k)
            out += Line.Row(k.name.lowercase().replaceFirstChar { it.uppercase() }, "${used.size} of ${s.bank(k).size} sounds used")
        }
        val usedWaves = s.waveInstruments.map { it.wave }.toSet()
        out += Line.Row("Waves", "${s.waves.size} tables, ${usedWaves.size} assigned to sounds")
        out += Line.Row("Notes", "${Songs.noteName(0)} to ${Songs.noteName(Songs.NOTE_MAX)}")
        out += Line.Row("Rows", "1 to ${Songs.ROWS} per pattern (L2+→ Length), ticks per row 1 to ${EditorSession.MAX_TICKS}")
        return out
    }

    private fun usedSlots(kind: InstrumentKind): Set<Int> {
        val s = song
        val used = HashSet<Int>()
        for (ch in 0 until s.channelCount()) {
            if (Songs.channelKind(s, ch) != kind) continue
            for (pat in s.orders.toSet()) for (cell in s.patterns[pat].tracks[ch]) if (cell.instrument > 0) used += cell.instrument
        }
        return used
    }

    override fun down(x: Float, y: Float): Boolean {
        tabHit = tabs.firstOrNull { it.first.contains(x, y) }?.second ?: -1
        touchY = y
        touchScroll = st.scroll
        dragging = false
        return true
    }

    override fun move(x: Float, y: Float) {
        if (tabHit >= 0) return
        val dy = y - touchY
        if (!dragging && Math.abs(dy) > lineH * 0.5f) dragging = true
        if (dragging) st.scroll = maxOf(0, touchScroll - (dy / lineH).toInt())
    }

    override fun up(x: Float, y: Float) {
        if (tabHit >= 0 && tabs.firstOrNull { it.first.contains(x, y) }?.second == tabHit) {
            st.helpPage = tabHit
            st.scroll = 0
        }
        tabHit = -1
        dragging = false
    }
}
