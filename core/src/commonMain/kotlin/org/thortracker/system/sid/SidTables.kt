package org.thortracker.system.sid

import org.thortracker.model.NumberText

/**
 * GoatTracker's four song-wide tables (255 rows each) as Thor Tracker builds them: the wave, pulse and filter tables
 * hold the instruments' programs ([SidProgram]), the speedtable the vibrato, portamento and funktempo values that
 * instruments and effects use ([SidSpeeds]).
 *
 * **Program placement** ([placement], [place]): identical programs of a table share rows. A program stays at the row it
 * was last placed at ([SidProgram.at]) while it fits there; one that no longer fits (it grew into a neighbour, or was
 * never placed) takes the first gap that holds it. [place] runs after an edit with the song before it, so programs that
 * did not change keep their rows and only the edited one moves; the native engine follows a playing program to its new
 * rows by instrument slot (docs/AUDIO_ENGINE.md, SID "Live edits"), so rows reached only through commands or jumps should
 * not move, which this guarantees for unchanged programs. When gaps are too fragmented for a program, every program is
 * packed again from row 1 in slot order. Lowering uses [placement] on the stored rows, so a song places the same way
 * every time it is played or exported.
 */
object SidTables {
    const val WAVE = 0
    const val PULSE = 1
    const val FILTER = 2
    const val SPEED = 3
    val NAMES = listOf("Wave", "Pulse", "Filter", "Speed")

    /** Rows per table (row numbers 1..255; 0 = no program / stop). */
    const val ROWS = 255

    /** Left byte of a jump row in every table. */
    const val JUMP = 0xFF

    // Wave table left side (GoatTracker editor values)
    const val WAVE_DELAY_MAX = 0x0F
    const val WAVE_FIRST = 0x10
    const val WAVE_LAST = 0xDF
    const val SILENT_FIRST = 0xE0
    const val SILENT_LAST = 0xEF
    const val WAVE_COMMAND = 0xF0

    // Wave table right side
    const val KEEP_FREQUENCY = 0x80
    const val RELATIVE_UP_MAX = 0x5F
    const val ABSOLUTE_LAST = 0xDF

    // SID waveform register bits
    const val GATE = 0x01
    const val SYNC = 0x02
    const val RING = 0x04
    const val TEST = 0x08
    const val TRIANGLE = 0x10
    const val SAW = 0x20
    const val PULSE_WAVE = 0x40
    const val NOISE = 0x80

    // Filter program "set filter" left side: $80 | passband bits
    const val LOWPASS = 0x10
    const val BANDPASS = 0x20
    const val HIGHPASS = 0x40

    /** Wave program commands that may run from a program: GoatTracker forbids 0, 8 (set wave program) and E. */
    val WAVE_COMMANDS = (1..13).toSet() - 8

    /** Wave right byte for a note [semitones] above (0..95) or below (-32..-1) the played note. */
    fun relativeNote(semitones: Int): Int {
        require(semitones in -32..RELATIVE_UP_MAX) { "relative notes are -32..+95 semitones" }
        return semitones and 0x7F
    }

    /** Semitones of a relative wave right byte (`$00-$7F`). */
    fun relativeOf(right: Int): Int = if (right <= RELATIVE_UP_MAX) right else right - 0x80

    /** Program of [slot]'s instrument (1-based) in [table]. */
    fun program(sound: SidSound, table: Int, slot: Int): SidProgram = sound.instruments[slot - 1].program(table)

    /**
     * Where the programs of [table] sit: [start] per slot (0 = no program), [owner]/[index] per row (the first slot of
     * the program on that row, 0 = free; the row's index within that program), [used] rows and [overflow] rows that
     * did not fit (then those programs start at 0 and the song cannot play).
     */
    class Placement(val table: Int, val start: IntArray, val owner: IntArray, val index: IntArray, val used: Int, val overflow: Int)

    /**
     * Places [table]'s programs of [sound] (see the class comment). With [previous], units unchanged since it (same
     * rows at the same stored row) are placed first, so the edited program is the one that moves.
     */
    fun placement(sound: SidSound, table: Int, previous: SidSound? = null): Placement {
        val units = LinkedHashMap<List<SidRow>, MutableList<Int>>()
        for (slot in 1..sound.instruments.size) {
            val p = program(sound, table, slot)
            if (p.rows.isNotEmpty()) units.getOrPut(p.rows) { ArrayList() } += slot
        }
        val start = IntArray(sound.instruments.size + 1)
        val owner = IntArray(ROWS + 1)
        val index = IntArray(ROWS + 1)
        fun size(rows: List<SidRow>) = rows.size + if (rows.last().isJump) 0 else 1
        fun fits(at: Int, n: Int) = at >= 1 && at + n - 1 <= ROWS && (at until at + n).none { owner[it] != 0 }
        fun put(rows: List<SidRow>, slots: List<Int>, at: Int) {
            for (i in 0 until size(rows)) { owner[at + i] = slots[0]; index[at + i] = i }
            for (s in slots) start[s] = at
        }
        val pending = ArrayList<Map.Entry<List<SidRow>, MutableList<Int>>>()
        if (previous != null) {
            for (u in units) {
                val kept = u.value.firstOrNull { s ->
                    val now = program(sound, table, s)
                    now.at > 0 && previous.instruments.size >= s && program(previous, table, s) == now
                }
                val at = kept?.let { program(sound, table, it).at }
                if (at != null && fits(at, size(u.key))) put(u.key, u.value, at) else pending += u
            }
        } else pending += units.entries
        val later = ArrayList<Map.Entry<List<SidRow>, MutableList<Int>>>()
        for (u in pending) {
            val n = size(u.key)
            val at = u.value.map { program(sound, table, it).at }.firstOrNull { fits(it, n) }
            if (at != null) put(u.key, u.value, at) else later += u
        }
        var overflow = 0
        for (u in later) {
            val n = size(u.key)
            val at = (1..ROWS - n + 1).firstOrNull { fits(it, n) }
            if (at != null) put(u.key, u.value, at) else overflow += n
        }
        if (overflow > 0) {
            // Too fragmented (or too much): pack everything from row 1.
            owner.fill(0); index.fill(0); start.fill(0)
            var at = 1
            overflow = 0
            for (u in units) {
                val n = size(u.key)
                if (at + n - 1 <= ROWS) { put(u.key, u.value, at); at += n } else overflow += n
            }
        }
        return Placement(table, start, owner, index, owner.count { it != 0 }, overflow)
    }

    /**
     * [sound] with every program's [SidProgram.at] set to where it is placed, the programs unchanged since [previous]
     * keeping their rows. Edits call this with the song before the edit.
     */
    fun place(previous: SidSound?, sound: SidSound): SidSound {
        val placements = (WAVE..FILTER).map { placement(sound, it, previous) }
        val instruments = sound.instruments.mapIndexed { i, ins ->
            var out = ins
            for (p in placements) {
                val prog = ins.program(p.table)
                if (prog.rows.isNotEmpty() && prog.at != p.start[i + 1]) out = out.withProgram(p.table, prog.copy(at = p.start[i + 1]))
                if (prog.rows.isEmpty() && prog.at != 0) out = out.withProgram(p.table, SidProgram())
            }
            out
        }
        return if (instruments == sound.instruments) sound else sound.copy(instruments = instruments)
    }

    /** "Pulse+gate", "Tri+Saw", "Noise sync"…: the waveform register value [w]. */
    fun waveformName(w: Int): String {
        val waves = listOfNotNull(
            "Tri".takeIf { w and TRIANGLE != 0 }, "Saw".takeIf { w and SAW != 0 },
            "Pulse".takeIf { w and PULSE_WAVE != 0 }, "Noise".takeIf { w and NOISE != 0 },
        )
        val bits = listOfNotNull("gate".takeIf { w and GATE != 0 }, "sync".takeIf { w and SYNC != 0 },
            "ring".takeIf { w and RING != 0 }, "test".takeIf { w and TEST != 0 })
        val base = if (waves.isEmpty()) "No wave" else waves.joinToString("+")
        return if (bits.isEmpty()) base else "$base ${bits.joinToString("+")}"
    }

    /** What the frequency byte [right] of a wave row does. */
    fun noteText(right: Int): String = when {
        right == KEEP_FREQUENCY -> "pitch kept"
        right > KEEP_FREQUENCY -> "note ${SidSystem.noteName(right - KEEP_FREQUENCY)}"
        relativeOf(right) == 0 -> "the note"
        relativeOf(right) > 0 -> "note +${relativeOf(right)}"
        else -> "note ${relativeOf(right)}"
    }

    /** Human reading of [row] in [table] (program form: jumps are program rows, commands take effect parameters). */
    fun describe(table: Int, row: SidRow): String {
        val l = row.left
        val r = row.right
        if (l == JUMP) return if (r == 0) "Stop" else "Go to row $r"
        return when (table) {
            WAVE -> when {
                l == 0 -> "No wave change, ${noteText(r)}"
                l <= WAVE_DELAY_MAX -> "Wait $l more tick${if (l == 1) "" else "s"}" + if (r != KEEP_FREQUENCY) ", ${noteText(r)}" else ""
                l <= WAVE_LAST -> "${waveformName(l)}, ${noteText(r)}"
                l <= SILENT_LAST -> "Silent ${waveformName(l and 0x0F)}, ${noteText(r)}"
                else -> "Command ${SidRules.effectText(l - WAVE_COMMAND, r)}: ${SidRules.describe(l - WAVE_COMMAND, r)}"
            }
            PULSE -> if (l >= 0x80) "Pulse width ${NumberText.hex(((l and 0x0F) shl 8) or r, 3)}"
                else "Pulse ${signed(r)} per tick for $l tick${if (l == 1) "" else "s"}"
            FILTER -> when {
                l == 0 -> "Cutoff ${NumberText.hex(r, 2)}"
                l < 0x80 -> "Cutoff ${signed(r)} per tick for $l tick${if (l == 1) "" else "s"}"
                else -> "${passbandName(l and 0x70)}, resonance ${NumberText.hex(r shr 4)}, ${routingName(r and 0x0F)}"
            }
            else -> "${NumberText.hex(l, 2)} ${NumberText.hex(r, 2)}"
        }
    }

    /** "Lowpass", "Low+band", … for passband bits ([LOWPASS] | [BANDPASS] | [HIGHPASS]). */
    fun passbandName(bits: Int): String {
        val parts = listOfNotNull("Low".takeIf { bits and LOWPASS != 0 }, "Band".takeIf { bits and BANDPASS != 0 },
            "High".takeIf { bits and HIGHPASS != 0 })
        return when (parts.size) {
            0 -> "No passband"
            1 -> parts[0] + "pass"
            else -> parts.joinToString("+")
        }
    }

    /** "voices 1+3", "no voices" for filter routing bits. */
    fun routingName(bits: Int): String {
        val voices = (0..2).filter { bits and (1 shl it) != 0 }.map { it + 1 }
        val text = if (voices.isEmpty()) "no voices" else "voice${if (voices.size > 1) "s" else ""} ${voices.joinToString("+")}"
        return if (bits and 8 != 0) "$text + external input" else text
    }

    private fun signed(b: Int): String = (if (b >= 0x80) b - 0x100 else b).let { if (it >= 0) "+$it" else "$it" }

    /**
     * Why row [i] (0-based) of a [table] program with [rows] rows cannot be lowered, or null. Checks the byte ranges
     * GoatTracker gives meaning to, jump targets (inside the program, not onto a jump), wave commands GoatTracker
     * allows in its wavetable and their parameters ([SidRules.paramProblem]).
     */
    fun rowProblem(table: Int, rows: List<SidRow>, i: Int): String? {
        val row = rows[i]
        val (l, r) = row.left to row.right
        if (l !in 0..0xFF || r !in 0..0xFF) return "bytes must be 00-FF"
        if (l == JUMP) return when {
            r > rows.size -> "jumps to row $r, past the program's ${rows.size} rows"
            r > 0 && rows[r - 1].isJump -> "jumps onto another jump (row $r); GoatTracker's playback is then undefined"
            r - 1 == i -> "jumps to itself"
            else -> null
        }
        return when (table) {
            WAVE -> when {
                i == 0 && l <= WAVE_DELAY_MAX -> "a wave program must start with a waveform (GoatTracker: a first-row delay or no-change misses notes)"
                l in WAVE_COMMAND..0xFE -> {
                    val c = l - WAVE_COMMAND
                    if (c !in WAVE_COMMANDS) "command ${SidRules.effectChar(c)} cannot run from a wave program (only 1-7 and 9-D)"
                    else SidRules.paramProblem(c, r)
                }
                l < WAVE_COMMAND && r > ABSOLUTE_LAST -> "note byte ${NumberText.hex(r, 2)}: notes are 00-7F relative, 80 kept, 81-DF absolute"
                else -> null
            }
            PULSE -> if (l == 0) "pulse rows are 01-7F (modulate) or 80-FE (set width)" else null
            FILTER -> if (l in 0x80..0xFE && l and 0x0F != 0) "set-filter rows are 80-F0 in steps of 10 (the low digit is unused)" else null
            else -> null
        }
    }
}
