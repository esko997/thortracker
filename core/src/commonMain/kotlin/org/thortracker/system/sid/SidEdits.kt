package org.thortracker.system.sid

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song

/** SID document edits: the one instrument bank and its programs, as undoable [EditorSession] commands. */

/** Instrument [slot] (1..[SidSystem.SLOTS]), or null. */
fun Song.sidInstrument(slot: Int): SidInstrument? = sid.instruments.getOrNull(slot - 1)

/** A new instrument for [slot], as a blank song has it. */
fun defaultSidInstrument(slot: Int) = SidInstrument(name = "Sound ${hex2(slot)}")

/** A copied SID instrument, or one of its programs. */
sealed interface SidClip : Clip {
    data class Sound(val instrument: SidInstrument) : SidClip
    data class Program(val table: Int, val rows: List<SidRow>) : SidClip
}

/**
 * Replaces instrument [slot]. Every edit goes through here so the table rows stay placed ([SidTables.place]): the
 * programs that did not change keep their rows and a playing song follows the edited one.
 */
fun EditorSession.setSidInstrument(slot: Int, instrument: SidInstrument, label: String = "Edit ${instrument.name.ifBlank { "sound" }}"): Boolean {
    if (slot !in 1..song.sid.instruments.size) return false
    return apply(label) { s ->
        val before = s.sid
        val after = before.copy(instruments = before.instruments.replaced(slot - 1, instrument))
        s.copy(sound = SidTables.place(before, after))
    }
}

fun EditorSession.editSidInstrument(slot: Int, label: String, f: (SidInstrument) -> SidInstrument): Boolean {
    val cur = song.sidInstrument(slot) ?: return false
    val next = f(cur)
    return next != cur && setSidInstrument(slot, next, label)
}

/** Edits the rows of [slot]'s program in [table] (rows past [SidPrograms.MAX_ROWS] are dropped). */
fun EditorSession.editSidProgram(slot: Int, table: Int, label: String, f: (List<SidRow>) -> List<SidRow>): Boolean =
    editSidInstrument(slot, label) { ins ->
        val p = ins.program(table)
        val rows = f(p.rows).take(SidPrograms.MAX_ROWS)
        if (rows == p.rows) ins else ins.withProgram(table, p.copy(rows = rows))
    }

/** Edits the song-wide chip settings. */
fun EditorSession.editSidSound(label: String, f: (SidSound) -> SidSound): Boolean =
    apply(label) { s -> f(s.sid).let { if (it == s.sid) s else s.copy(sound = it) } }

fun EditorSession.copySidInstrument(slot: Int) {
    val i = song.sidInstrument(slot) ?: return
    hold(SidClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copySidProgram(slot: Int, table: Int) {
    val i = song.sidInstrument(slot) ?: return
    hold(SidClip.Program(table, i.program(table).rows))
    note("Copied the ${SidTables.NAMES[table].lowercase()} program of sound ${hex2(slot)}")
}

/** Pastes the clipboard into [slot]: a sound replaces it; a program replaces [table]'s program when the tables match. */
fun EditorSession.pasteSid(slot: Int, table: Int): Boolean = when (val clip = clipboard) {
    is SidClip.Sound -> setSidInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is SidClip.Program -> when {
        table !in SidTables.WAVE..SidTables.FILTER -> { note("Show a program page to paste a program"); false }
        clip.table != table -> { note("Clipboard holds a ${SidTables.NAMES[clip.table].lowercase()} program; show that page to paste it"); false }
        else -> editSidProgram(slot, table, "Paste ${SidTables.NAMES[table].lowercase()} program") { clip.rows }
            .also { note("Pasted the ${SidTables.NAMES[table].lowercase()} program into sound ${hex2(slot)}") }
    }
    else -> { note("Clipboard holds no SID sound or program"); false }
}

/**
 * Program row editing for the instrument view: what kind of step a row is, its editable columns, and row
 * insert/delete that keep the program's jumps on the rows they pointed at. Rows are in [SidRow]'s program form
 * (jump targets are program rows, 1-based).
 */
object SidPrograms {
    /** Most rows one program may have (a table has 255; the stop row takes one more). */
    const val MAX_ROWS = 64

    /** The kinds of step a program row can be, per table. */
    enum class Kind(val label: String) {
        WAVEFORM("Wave"), SILENT("Silent"), DELAY("Wait"), HOLD("Hold"), COMMAND("Command"),
        PULSE_SET("Width"), MODULATE("Sweep"),
        FILTER_SET("Filter"), CUTOFF("Cutoff"),
        JUMP("Jump"),
    }

    val KINDS = listOf(
        listOf(Kind.WAVEFORM, Kind.SILENT, Kind.DELAY, Kind.HOLD, Kind.COMMAND, Kind.JUMP),
        listOf(Kind.PULSE_SET, Kind.MODULATE, Kind.JUMP),
        listOf(Kind.FILTER_SET, Kind.CUTOFF, Kind.MODULATE, Kind.JUMP),
    )

    fun kindOf(table: Int, row: SidRow): Kind {
        val l = row.left
        if (l == SidTables.JUMP) return Kind.JUMP
        return when (table) {
            SidTables.WAVE -> when {
                l == 0 -> Kind.HOLD
                l <= SidTables.WAVE_DELAY_MAX -> Kind.DELAY
                l <= SidTables.WAVE_LAST -> Kind.WAVEFORM
                l <= SidTables.SILENT_LAST -> Kind.SILENT
                else -> Kind.COMMAND
            }
            SidTables.PULSE -> if (l >= 0x80) Kind.PULSE_SET else Kind.MODULATE
            else -> when {
                l == 0 -> Kind.CUTOFF
                l < 0x80 -> Kind.MODULATE
                else -> Kind.FILTER_SET
            }
        }
    }

    /** The waveform shapes a wave row can play (high nibble 1-D: E and F are silent rows and commands). */
    val SHAPES = 1..13

    fun shapeName(nibble: Int): String = SidTables.waveformName(nibble shl 4)

    /** How a wave row sets the frequency: the note plus semitones, an absolute note, or unchanged. */
    enum class NoteMode(val label: String) { RELATIVE("rel"), ABSOLUTE("abs"), KEEP("keep") }

    fun noteMode(right: Int): NoteMode = when {
        right == SidTables.KEEP_FREQUENCY -> NoteMode.KEEP
        right > SidTables.KEEP_FREQUENCY -> NoteMode.ABSOLUTE
        else -> NoteMode.RELATIVE
    }

    /** Absolute notes a wave row can name: C#0..B-7 (`$81-$DF`). */
    val ABSOLUTE = 1..(SidTables.ABSOLUTE_LAST - SidTables.KEEP_FREQUENCY)

    /** Relative semitones a wave row can name. */
    val RELATIVE = -32..SidTables.RELATIVE_UP_MAX

    /** A wave row's frequency byte in [mode], converted from [right] so the pitch stays close. */
    fun withMode(right: Int, mode: NoteMode): Int = when (mode) {
        NoteMode.KEEP -> SidTables.KEEP_FREQUENCY
        NoteMode.RELATIVE -> SidTables.relativeNote(0)
        NoteMode.ABSOLUTE -> SidTables.KEEP_FREQUENCY + (if (noteMode(right) == NoteMode.RELATIVE) 48 + SidTables.relativeOf(right) else 48)
            .coerceIn(ABSOLUTE)
    }

    /** A new row of [kind], carrying what it can of [from] (the note of a wave row, the pulse width's range). */
    fun convert(table: Int, from: SidRow?, kind: Kind): SidRow {
        val note = from?.takeIf { table == SidTables.WAVE && it.left < SidTables.WAVE_COMMAND }?.right ?: SidTables.relativeNote(0)
        return when (kind) {
            Kind.WAVEFORM -> SidRow(SidTables.SAW or SidTables.GATE, note)
            Kind.SILENT -> {
                // A waveform row turned silent keeps its gate/sync/ring/test bits.
                val bits = if (from != null && kindOf(table, from) == Kind.WAVEFORM) from.left and 0x0F else 0
                SidRow(SidTables.SILENT_FIRST or bits, note)
            }
            Kind.DELAY -> SidRow(1, SidTables.KEEP_FREQUENCY)
            Kind.HOLD -> SidRow(0, note)
            Kind.COMMAND -> SidRow.command(SidRules.FX_VIBRATO, 0x23)
            Kind.PULSE_SET -> SidRow.pulseWidth(0x800)
            Kind.MODULATE -> SidRow.modulate(0x20, if (table == SidTables.PULSE) 0x20 else -2)
            Kind.FILTER_SET -> SidRow.filter(SidTables.LOWPASS, 0xA, 1)
            Kind.CUTOFF -> SidRow.cutoff(0x80)
            Kind.JUMP -> SidRow.jump(1)
        }
    }

    /** The row an empty program starts with, or the one appended after [last]. */
    fun appended(table: Int, last: SidRow?): SidRow = when {
        last != null && !last.isJump -> last
        table == SidTables.WAVE -> SidRow.wave(SidTables.SAW or SidTables.GATE)
        table == SidTables.PULSE -> SidRow.pulseWidth(0x800)
        else -> SidRow.filter(SidTables.LOWPASS, 0xA, 1)
    }

    /** [rows] with [row] inserted at [at]; jumps to rows at or after it keep their targets. */
    fun inserted(rows: List<SidRow>, at: Int, row: SidRow): List<SidRow> {
        val i = at.coerceIn(0, rows.size)
        val shifted = rows.map { r -> if (r.isJump && r.right > i) r.copy(right = r.right + 1) else r }
        return (shifted.subList(0, i) + row + shifted.subList(i, shifted.size)).take(MAX_ROWS)
    }

    /** [rows] without row [at]; jumps after it keep their targets, jumps to it go to the row that takes its place. */
    fun removed(rows: List<SidRow>, at: Int): List<SidRow> {
        if (at !in rows.indices) return rows
        val out = rows.toMutableList().also { it.removeAt(at) }
        return out.map { r ->
            when {
                !r.isJump || r.right <= at -> r
                r.right == at + 1 -> r.copy(right = if (at < out.size) at + 1 else 0)
                else -> r.copy(right = r.right - 1)
            }
        }
    }

    /** [rows] cut or extended (repeating the last row, or a fresh one after a jump) to [length] rows. */
    fun withLength(table: Int, rows: List<SidRow>, length: Int): List<SidRow> {
        val n = length.coerceIn(0, MAX_ROWS)
        if (n == rows.size) return rows
        if (n < rows.size) return rows.take(n).map { r -> if (r.isJump && r.right > n) r.copy(right = 0) else r }
        val out = rows.toMutableList()
        while (out.size < n) out += appended(table, out.lastOrNull())
        return out
    }

    // ---- columns ---------------------------------------------------------------------------------

    /**
     * One editable column of a program row: [label] above it, [text] its value; [step] changes it by a fine (←→) and
     * coarse (↑↓) amount; [toggle] is for on/off bits (Edit tap flips it).
     */
    class Field(val label: String, val text: String, val toggle: Boolean = false, val step: (SidRow, Int, Int) -> SidRow)

    private fun signed(b: Int) = if (b >= 0x80) b - 0x100 else b

    private fun signedText(b: Int) = signed(b).let { if (it > 0) "+$it" else "$it" }

    private fun bit(row: SidRow, mask: Int, label: String, inRight: Boolean = false): Field {
        val on = (if (inRight) row.right else row.left) and mask != 0
        return Field(label, if (on) label else "·", toggle = true) { r, f, c ->
            if (f == 0 && c == 0) r
            else if (inRight) r.copy(right = r.right xor mask) else r.copy(left = r.left xor mask)
        }
    }

    private fun noteFields(row: SidRow): List<Field> {
        val mode = noteMode(row.right)
        val modeField = Field("Pitch", mode.label) { r, f, c ->
            val next = NoteMode.entries[(noteMode(r.right).ordinal + f + c).mod(NoteMode.entries.size)]
            r.copy(right = withMode(r.right, next))
        }
        val valueText = when (mode) {
            NoteMode.KEEP -> "—"
            NoteMode.ABSOLUTE -> SidSystem.noteName(row.right - SidTables.KEEP_FREQUENCY)
            NoteMode.RELATIVE -> SidTables.relativeOf(row.right).let { if (it > 0) "+$it" else "$it" }
        }
        val value = Field("Note", valueText) { r, f, c ->
            when (noteMode(r.right)) {
                NoteMode.KEEP -> r
                NoteMode.ABSOLUTE -> r.copy(right = SidTables.KEEP_FREQUENCY + (r.right - SidTables.KEEP_FREQUENCY + f + 12 * c).coerceIn(ABSOLUTE))
                NoteMode.RELATIVE -> r.copy(right = SidTables.relativeNote((SidTables.relativeOf(r.right) + f + 12 * c).coerceIn(RELATIVE)))
            }
        }
        return listOf(modeField, value)
    }

    /** The editable columns of [row] in a [table] program of [size] rows; the first is always its [Kind]. */
    fun fields(table: Int, row: SidRow, size: Int): List<Field> {
        val kind = kindOf(table, row)
        val kinds = KINDS[table]
        val type = Field("Step", kind.label) { r, f, c ->
            val d = f + c
            if (d == 0) r else convert(table, r, kinds[(kinds.indexOf(kindOf(table, r)) + d).mod(kinds.size)])
        }
        val l = row.left
        val r = row.right
        return listOf(type) + when (kind) {
            Kind.WAVEFORM -> listOf(
                Field("Wave", shapeName(l shr 4)) { x, f, c ->
                    val shape = ((x.left shr 4) + f + c).coerceIn(SHAPES)
                    x.copy(left = (shape shl 4) or (x.left and 0x0F))
                },
                bit(row, SidTables.GATE, "G"), bit(row, SidTables.SYNC, "S"), bit(row, SidTables.RING, "R"), bit(row, SidTables.TEST, "T"),
            ) + noteFields(row)
            Kind.SILENT -> listOf(
                bit(row, SidTables.GATE, "G"), bit(row, SidTables.SYNC, "S"), bit(row, SidTables.RING, "R"), bit(row, SidTables.TEST, "T"),
            ) + noteFields(row)
            Kind.DELAY -> listOf(Field("Ticks", l.toString()) { x, f, c -> x.copy(left = (x.left + f + 4 * c).coerceIn(1, SidTables.WAVE_DELAY_MAX)) }) + noteFields(row)
            Kind.HOLD -> noteFields(row)
            Kind.COMMAND -> {
                val commands = SidTables.WAVE_COMMANDS.sorted()
                val c = l - SidTables.WAVE_COMMAND
                listOf(
                    Field("Cmd", "${SidRules.effectChar(c)} ${SidRules.effect(c)?.name ?: ""}") { x, f, k ->
                        val i = commands.indexOf(x.left - SidTables.WAVE_COMMAND).coerceAtLeast(0)
                        x.copy(left = SidTables.WAVE_COMMAND + commands[(i + f + k).mod(commands.size)])
                    },
                    Field("Value", hex2(r)) { x, f, k -> x.copy(right = (x.right + f + 16 * k).coerceIn(0, 0xFF)) },
                )
            }
            Kind.PULSE_SET -> {
                val w = ((l and 0x0F) shl 8) or r
                listOf(Field("Width", "${org.thortracker.model.NumberText.hex(w, 3)} ${w * 100 / 4096}%") { x, f, k ->
                    val nw = ((((x.left and 0x0F) shl 8) or x.right) + 0x10 * f + 0x100 * k).coerceIn(0, 0xFFF)
                    SidRow.pulseWidth(nw)
                })
            }
            Kind.MODULATE -> listOf(
                Field("Ticks", l.toString()) { x, f, k -> x.copy(left = (x.left + f + 16 * k).coerceIn(1, 0x7F)) },
                Field("Speed", signedText(r)) { x, f, k -> x.copy(right = (signed(x.right) + f + 16 * k).coerceIn(-128, 127) and 0xFF) },
            )
            Kind.FILTER_SET -> listOf(
                bit(row, SidTables.LOWPASS, "Lo"), bit(row, SidTables.BANDPASS, "Bd"), bit(row, SidTables.HIGHPASS, "Hi"),
                Field("Res", org.thortracker.model.NumberText.hex(r shr 4)) { x, f, k ->
                    x.copy(right = (((x.right shr 4) + f + 4 * k).coerceIn(0, 15) shl 4) or (x.right and 0x0F))
                },
                bit(row, 1, "V1", inRight = true), bit(row, 2, "V2", inRight = true), bit(row, 4, "V3", inRight = true),
            )
            Kind.CUTOFF -> listOf(Field("Cutoff", hex2(r)) { x, f, k -> x.copy(right = (x.right + f + 16 * k).coerceIn(0, 0xFF)) })
            Kind.JUMP -> listOf(Field("To", if (r == 0) "stop" else "row ${r}") { x, f, k -> x.copy(right = (x.right + f + 4 * k).coerceIn(0, size)) })
        }
    }

    // ---- what a program does over time, for the pulse and filter graphs ----------------------------

    /**
     * The value a pulse (12-bit width) or filter (8-bit cutoff) program holds on each of its first [ticks] ticks,
     * starting from [start] (the value before the program), as GoatTracker's player steps it: one row per tick, a
     * modulation row lasting its tick count, jumps taking no tick. Stops (the value holds) at a stop or a jump loop
     * without time.
     */
    fun trace(table: Int, rows: List<SidRow>, ticks: Int, start: Int): IntArray {
        val out = IntArray(ticks)
        var value = start
        var i = 0
        var left = 0
        var stopped = rows.isEmpty()
        val max = if (table == SidTables.PULSE) 0xFFF else 0xFF
        for (t in 0 until ticks) {
            var guard = 0
            while (!stopped && left == 0) {
                if (i !in rows.indices) { stopped = true; break }
                val row = rows[i]
                if (row.isJump) {
                    if (row.right == 0 || ++guard > rows.size) { stopped = true; break }
                    i = row.right - 1
                    continue
                }
                when (kindOf(table, row)) {
                    Kind.PULSE_SET -> { value = ((row.left and 0x0F) shl 8) or row.right; i++; left = -1 }
                    Kind.CUTOFF -> { value = row.right; i++; left = -1 }
                    Kind.FILTER_SET -> { i++; continue } // sets passband/routing; the cutoff follows on this tick
                    Kind.MODULATE -> left = row.left
                    else -> { i++; left = -1 }
                }
            }
            if (left > 0) {
                value = (value + signed(rows[i].right)).let { if (table == SidTables.PULSE) it and 0xFFF else it.coerceIn(0, max) }
                if (--left == 0) i++
            } else if (left < 0) left = 0
            out[t] = value
        }
        return out
    }
}
