package org.thortracker.model

import org.thortracker.backend.HugeLimits

/**
 * GB/hUGE effect and cell semantics, verified against the pinned hUGEDriver 6.1.3 source
 * (`rgbds/hUGEDriver.asm`, effect jump table at `do_effect.jump`) and hUGETracker 1.0.11.
 * Effect parameters are raw driver bytes; descriptions translate them, rules reject anything the
 * driver would ignore, misinterpret or that the export toolchain cannot represent.
 * The validator, editor hints, lowering and `.uge` export all use these rules.
 */
data class EffectInfo(
    val code: Int,
    val name: String,
    /** Parameter layout, e.g. "xy: x=up, y=down". */
    val param: String,
    val description: String,
    /** False when the effect can never be exported/previewed (6xx). */
    val supported: Boolean,
)

object HugeRules {
    const val NO_NOTE = 90
    const val FX_ARPEGGIO = 0x0
    const val FX_PORTA_UP = 0x1
    const val FX_PORTA_DOWN = 0x2
    const val FX_TONE_PORTA = 0x3
    const val FX_VIBRATO = 0x4
    const val FX_MASTER_VOLUME = 0x5
    const val FX_CALL_ROUTINE = 0x6
    const val FX_NOTE_DELAY = 0x7
    const val FX_PAN = 0x8
    const val FX_TIMBRE = 0x9
    const val FX_VOLUME_SLIDE = 0xA
    const val FX_POSITION_JUMP = 0xB
    const val FX_SET_VOLUME = 0xC
    const val FX_PATTERN_BREAK = 0xD
    const val FX_NOTE_CUT = 0xE
    const val FX_SET_SPEED = 0xF

    /** hUGEDriver's `order_cnt` is one byte holding orders * 2. */
    const val MAX_ORDERS = HugeLimits.MAX_ORDERS
    /** hUGETracker stores names/title/artist/comment as Pascal ShortString (length byte + 255 bytes). */
    const val MAX_TEXT_BYTES = 255

    val CHANNEL_KINDS = listOf(InstrumentKind.PULSE, InstrumentKind.PULSE, InstrumentKind.WAVE, InstrumentKind.NOISE)
    val CHANNEL_NAMES = listOf("Pulse 1", "Pulse 2", "Wave", "Noise")
    /** Only CH1 has the NR10 frequency-sweep unit; the driver writes NR10 for channel 0 only. */
    const val SWEEP_CHANNEL = 0

    val EFFECTS = listOf(
        EffectInfo(0x0, "Arpeggio", "xy: +x, +y semitones",
            "Cycle each tick between the note, note+x and note+y semitones. 000 = no effect. Not on Noise.", true),
        EffectInfo(0x1, "Slide up", "xx: units per tick",
            "Raise the pitch by xx period units every tick after the first. Not on Noise.", true),
        EffectInfo(0x2, "Slide down", "xx: units per tick",
            "Lower the pitch by xx period units every tick after the first. Not on Noise.", true),
        EffectInfo(0x3, "Tone portamento", "xx: units per tick",
            "Slide toward this row's note instead of playing it, stopping exactly on it. Not on Noise.", true),
        EffectInfo(0x4, "Vibrato", "xy: x=tick mask, y=depth",
            "Raise the note by y units on ticks where (running tick counter AND x) = 0, else play it plain: " +
                "x=0 constant detune, 1/3/7/F raised 1 of every 2/4/8/16 ticks. Not on Noise.", true),
        EffectInfo(0x5, "Master volume", "xy: NR50 (x=left, y=right; 0-7 each)",
            "Write NR50 for both speakers. Global. Volume 0 is quiet, not silent.", true),
        EffectInfo(0x6, "Call routine", "xy: routine y",
            "User assembly routines are not part of Thor Tracker projects and are exported as NULL by uge2source.", false),
        EffectInfo(0x7, "Note delay", "xx: ticks",
            "Start this row's note after xx ticks. A delay of the row length or more skips it.", true),
        EffectInfo(0x8, "Panning", "xx: NR51 bits (hi nibble left, lo right; bit0=Pulse 1..bit3=Noise)",
            "Route channels to the left/right speakers. Global.", true),
        EffectInfo(0x9, "Timbre", "xx: duty/NRx1, wave index, or noise width",
            "Pulse: write NRx1 (duty in bits 6-7). Wave: load wave xx (00-0F) and retrigger. Noise: 00 = 15-bit, 08 = 7-bit.", true),
        EffectInfo(0xA, "Volume slide", "xy: x=up, y=down",
            "Retrigger at the instrument's initial volume +x or -y. One nibble must be 0. Not on Wave.", true),
        EffectInfo(0xB, "Position jump", "xx: order+1 (00 = next order)",
            "After this row, continue at order xx-1 (B01 = first order). B00 continues with the next order.", true),
        EffectInfo(0xC, "Set volume", "xy: y=volume, x=envelope bits",
            "Pulse/Noise: set volume y/15 and restart the note (a legato note without instrument is not retriggered). " +
                "Wave: 0 mute, 1-4 25%, 5-9 50%, A-F 100%, no restart; x must be 0.", true),
        EffectInfo(0xD, "Pattern break", "xx: row+1 (01-40)",
            "After this row, continue with the next order at row xx-1 (D01 = row 0).", true),
        EffectInfo(0xE, "Note cut", "xx: ticks",
            "Silence the channel after xx ticks (E00 = immediately, the same as OFF). Must be less than the row length.", true),
        EffectInfo(0xF, "Set speed", "xx: ticks per row (01-FF)",
            "Change the number of ticks per row from this row on. Global.", true),
    )

    fun channelKind(channel: Int): InstrumentKind = CHANNEL_KINDS[channel]

    /** hUGEDriver 6.1.3 `include/hUGE_note_table.inc`: the 11-bit period of each note 0..71 (C-3..B-8). */
    val NOTE_PERIODS = intArrayOf(
        44, 156, 262, 363, 457, 547, 631, 710, 786, 854, 923, 986,
        1046, 1102, 1155, 1205, 1253, 1297, 1339, 1379, 1417, 1452, 1486, 1517,
        1546, 1575, 1602, 1627, 1650, 1673, 1694, 1714, 1732, 1750, 1767, 1783,
        1798, 1812, 1825, 1837, 1849, 1860, 1871, 1881, 1890, 1899, 1907, 1915,
        1923, 1930, 1936, 1943, 1949, 1954, 1959, 1964, 1969, 1974, 1978, 1982,
        1985, 1988, 1992, 1995, 1998, 2001, 2004, 2006, 2009, 2011, 2013, 2015,
    )

    /**
     * Pulse frequency in Hz of [note] raised by [detune] period units: 131072 / (2048 - period). The wave channel
     * sounds an octave lower for the same period, so intervals are identical.
     */
    fun pitchHz(note: Int, detune: Int): Double {
        val period = NOTE_PERIODS[note] + detune
        require(detune >= 0 && period <= 2047) { "detune $detune on note $note is outside the 11-bit period" }
        return 131072.0 / (2048 - period)
    }

    /**
     * The constant 40y detune [cell] applies to its note: 0 without an effect, y for vibrato with tick mask 0 (4 0y),
     * null for any other effect (the row's only effect slot is taken, so it cannot carry a tuning detune).
     */
    fun detuneOf(cell: Cell): Int? = when {
        !cell.hasEffect -> 0
        cell.effect == FX_VIBRATO && cell.param shr 4 == 0 -> cell.param and 0xF
        else -> null
    }

    /**
     * Pitch effects whose CH4 path is broken in hUGEDriver 6.1.3: `update_channel_freq.update_channel4` expects
     * a note ID in E, but arpeggio/vibrato pass a computed period, slides add to the stored poly byte, and tone
     * portamento suppresses the trigger (hUGEDriver.asm fx_arpeggio, fx_vibrato, fx_porta_*, fx_toneporta).
     */
    val NOISE_UNSAFE_PITCH_EFFECTS = setOf(FX_ARPEGGIO, FX_PORTA_UP, FX_PORTA_DOWN, FX_TONE_PORTA, FX_VIBRATO)

    /** NR43 value hUGEDriver 6.1.3 `get_note_poly` computes for a noise note (before the 7-bit width bit). */
    fun noisePoly(note: Int): Int {
        val a = (note + 192).inv() and 0xFF
        if (a < 7) return a
        val shift = ((a shr 2) - 1) and 0xFF
        val swapped = ((shift shl 4) or (shift shr 4)) and 0xFF
        return swapped or ((a and 3) + 4)
    }

    /**
     * Why a noise [note] does not sound like its name, or null. Pan Docs (Audio Registers, NR43): clock shift
     * 14 or 15 stops the LFSR, so those notes produce no noise; notes 64+ wrap inside `get_note_poly`.
     */
    fun noiseNoteProblem(note: Int): String? {
        if (note !in 0..Songs.NOTE_MAX) return null
        val poly = noisePoly(note)
        val name = Songs.noteName(note)
        return when {
            poly shr 4 >= 14 -> "noise note $name gives NR43 ${hex2(poly)} (clock shift ${poly shr 4}), which never " +
                "clocks the LFSR, so it plays no noise; use E-3..D#8"
            note >= 64 -> "noise note $name wraps around in hUGEDriver to NR43 ${hex2(poly)}, the same low rumble as " +
                "${Songs.noteName((4..63).first { noisePoly(it) == poly })}; use E-3..D#8"
            else -> null
        }
    }

    /** The cell as hUGEDriver receives it: OFF ([Cell.CUT]) is no note + E00. Equal results mean identical driver bytes. */
    fun lowered(cell: Cell): Cell = if (cell.note == Cell.CUT) Cell(Cell.EMPTY, 0, FX_NOTE_CUT, 0) else cell

    /**
     * [pattern]'s tracks as hUGEDriver plays them. hUGE patterns are always 64 rows, so a shorter [Pattern.length]
     * blanks the hidden rows and ends on its last row with D01 (break to row 0 of the next order) in the first free
     * effect slot, unless that row already ends the pattern with a pattern break or position jump. [tracks] lets the
     * loop plan supply its edited final-order copy. Returns [tracks] itself for a full-length pattern.
     */
    fun playedTracks(pattern: Pattern, tracks: List<List<Cell>> = pattern.tracks): List<List<Cell>> {
        val last = pattern.length - 1
        if (last >= Songs.ROWS - 1) return tracks
        val breakChannel = if (endsPattern(tracks, last)) -1 else freeEffectChannel(tracks, last) ?: -1
        return tracks.mapIndexed { ch, track ->
            List(track.size) { row ->
                when {
                    row > last -> Cell.BLANK
                    row == last && ch == breakChannel -> track[row].copy(effect = FX_PATTERN_BREAK, param = 1)
                    else -> track[row]
                }
            }
        }
    }

    /** True when [pattern] is shorter than 64 rows but its last row has no free effect slot for the ending D01. */
    fun lengthBreakConflict(pattern: Pattern): Boolean {
        val last = pattern.length - 1
        return last < Songs.ROWS - 1 && !endsPattern(pattern.tracks, last) && freeEffectChannel(pattern.tracks, last) == null
    }

    /** A row already leaves the pattern: Dxx (xx > 0) or any Bxx; OFF cells use their slot for E00. */
    private fun endsPattern(tracks: List<List<Cell>>, row: Int): Boolean = tracks.any { track ->
        val c = track[row]
        c.note != Cell.CUT && ((c.effect == FX_PATTERN_BREAK && c.param != 0) || c.effect == FX_POSITION_JUMP)
    }

    private fun freeEffectChannel(tracks: List<List<Cell>>, row: Int): Int? =
        tracks.indices.firstOrNull { ch -> tracks[ch][row].let { it.note != Cell.CUT && !it.hasEffect } }

    /** The final order's tracks as played: its pattern with the loop plan's edits, then its length ending. */
    fun finalOrderTracks(song: Song, plan: LoopPlan): List<List<Cell>> {
        val pattern = song.patterns[song.orders.last()]
        return playedTracks(pattern, pattern.tracks.mapIndexed { ch, track -> plan.tracks[ch] ?: track })
    }

    /** Distinct lowered tracks referenced by the order list, including the final order's loop/length variants. */
    fun distinctReferencedTracks(song: Song, plan: LoopPlan): Int {
        val referenced = HashSet<List<Cell>>()
        val last = song.orders.size - 1
        for (p in song.orders.subList(0, last).toSet()) referenced += playedTracks(song.patterns[p])
        referenced += finalOrderTracks(song, plan)
        val distinct = HashSet<List<Cell>>(referenced.size * 2)
        for (track in referenced) distinct += if (track.any { it.note == Cell.CUT }) track.map(::lowered) else track
        return distinct.size
    }

    fun bank(song: Song, kind: InstrumentKind): List<Instrument> = when (kind) {
        InstrumentKind.PULSE -> song.pulseInstruments
        InstrumentKind.WAVE -> song.waveInstruments
        InstrumentKind.NOISE -> song.noiseInstruments
    }

    fun hex2(value: Int) = NumberText.hex(value, 2)
    fun effectText(effect: Int, param: Int) = NumberText.hex(effect) + NumberText.hex(param, 2)

    /**
     * The first reason [cell] cannot be exported/previewed on [channel], or null.
     * Assumes structurally valid ranges (see [SongValidator.structuralErrors]).
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        val kind = channelKind(channel)
        if (cell.note == Cell.CUT) {
            if (cell.instrument != 0) return "OFF cannot load instrument ${hex2(cell.instrument)}; clear the instrument"
            if (cell.hasEffect) return "OFF is exported as effect E00 and needs this row's effect slot, " +
                "but the row has ${effectText(cell.effect, cell.param)}; move the effect or use Exx instead of OFF"
        } else if (cell.note == Cell.EMPTY && cell.instrument != 0) {
            return "instrument ${hex2(cell.instrument)} without a note is ignored by hUGEDriver; add a note or clear the instrument"
        }
        if (cell.note >= 0 && cell.instrument != 0 && channel != SWEEP_CHANNEL) {
            val instrument = bank(song, kind)[cell.instrument - 1]
            if (instrument.usesSweep) return "instrument ${hex2(cell.instrument)} \"${instrument.name}\" uses frequency sweep, " +
                "which only ${CHANNEL_NAMES[SWEEP_CHANNEL]} has; turn its sweep off or play it on ${CHANNEL_NAMES[SWEEP_CHANNEL]}"
        }
        if (!cell.hasEffect) return null
        val p = cell.param
        val x = p shr 4
        val y = p and 0xF
        val fx = effectText(cell.effect, p)
        if (kind == InstrumentKind.NOISE && cell.effect in NOISE_UNSAFE_PITCH_EFFECTS) {
            return "${EFFECTS[cell.effect].name.lowercase()} ($fx) is not supported on Noise: hUGEDriver 6.1.3 reads a " +
                "pitch period or offset NR43 value as a noise note, giving an unrelated noise pitch; enter separate noise notes instead"
        }
        return when (cell.effect) {
            FX_PORTA_UP, FX_PORTA_DOWN -> if (p == 0) "$fx slides by 0 and does nothing; use a speed of 01 or more" else null
            FX_TONE_PORTA -> if (p == 0) "300 never reaches the note; use a speed of 01 or more" else null
            FX_VIBRATO -> if (y == 0) "$fx has depth 0 and does nothing; set the low digit to 1-F" else null
            FX_CALL_ROUTINE -> "6xx calls user routines, which this project cannot provide (uge2source exports them as NULL)"
            FX_NOTE_DELAY -> if (p == 0) "700 never plays the note; use 701 or more, or remove the note" else null
            FX_TIMBRE -> when (kind) {
                InstrumentKind.WAVE -> if (p > 0x0F) "$fx loads wave ${hex2(p)}, but only waves 00-0F exist" else null
                InstrumentKind.NOISE -> if (p != 0x00 && p != 0x08) "on Noise, 9xx only selects the LFSR width: 900 = 15-bit, 908 = 7-bit" else null
                InstrumentKind.PULSE -> null
            }
            FX_VOLUME_SLIDE -> when {
                kind == InstrumentKind.WAVE -> "volume slide (Axy) is not supported on the Wave channel; use Cxy"
                x != 0 && y != 0 -> "$fx slides up and down at once; one digit must be 0"
                else -> null
            }
            FX_POSITION_JUMP -> if (p > song.orders.size) {
                "$fx would jump to order ${p - 1}, but the song has ${song.orders.size} orders (B01-${hex2(song.orders.size)}; B00 = next order)"
            } else null
            FX_SET_VOLUME -> if (kind == InstrumentKind.WAVE && x != 0) {
                "the Wave channel has no envelope; use C0${NumberText.hex(y)} (x must be 0)"
            } else null
            FX_PATTERN_BREAK -> when {
                p == 0 -> "D00 does nothing in hUGEDriver; use D01 to break to row 0 of the next order"
                p > Songs.ROWS -> "$fx would break to row ${p - 1}; use D01-${hex2(Songs.ROWS)} (rows 0-${Songs.ROWS - 1})"
                else -> null
            }
            FX_SET_SPEED -> if (p == 0) "F00 would make rows 256 ticks long; use F01-FF" else null
            else -> null
        }
    }

    fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String {
        val kind = channelKind(channel)
        val x = param shr 4
        val y = param and 0xF
        if (kind == InstrumentKind.NOISE && effect in NOISE_UNSAFE_PITCH_EFFECTS && (effect != FX_ARPEGGIO || param != 0)) {
            return "${EFFECTS[effect].name}: not supported on Noise"
        }
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_PORTA_UP -> "Slide up $param units per tick"
            FX_PORTA_DOWN -> "Slide down $param units per tick"
            FX_TONE_PORTA -> "Tone portamento toward the row's note, $param units per tick"
            FX_VIBRATO -> "Vibrato +$y units " + when (x) {
                0 -> "on every tick (constant detune)"
                1, 3, 7, 15 -> "on 1 of every ${x + 1} ticks"
                else -> "on ticks where tick AND ${NumberText.hex(x)} = 0"
            }
            FX_MASTER_VOLUME -> "Master volume left ${x and 7}/7, right ${y and 7}/7" +
                (if (param and 0x88 != 0) " (VIN bits set)" else "")
            FX_CALL_ROUTINE -> "Call routine $y (unsupported)"
            FX_NOTE_DELAY -> "Delay note by $param ticks"
            FX_PAN -> "Pan " + CHANNEL_NAMES.indices.joinToString(", ") { ch ->
                val left = param and (0x10 shl ch) != 0
                val right = param and (1 shl ch) != 0
                CHANNEL_NAMES[ch] + " " + when { left && right -> "L+R"; left -> "L"; right -> "R"; else -> "off" }
            }
            FX_TIMBRE -> when (kind) {
                InstrumentKind.PULSE -> "Duty ${listOf("12.5%", "25%", "50%", "75%")[param shr 6]}" +
                    (if (param and 0x3F != 0) ", length bits ${hex2(param and 0x3F)}" else "")
                InstrumentKind.WAVE -> "Load wave ${hex2(param)} and retrigger"
                InstrumentKind.NOISE -> if (param == 0x08) "Noise width 7-bit" else "Noise width 15-bit"
            }
            FX_VOLUME_SLIDE -> if (x != 0) "Volume slide up $x" else "Volume slide down $y"
            FX_POSITION_JUMP -> if (param == 0) "Jump to the next order" else "Jump to order ${param - 1}"
            FX_SET_VOLUME -> if (kind == InstrumentKind.WAVE) {
                "Wave volume " + when { y >= 10 -> "100%"; y >= 5 -> "50%"; y >= 1 -> "25%"; else -> "mute" }
            } else "Set volume $y/15" + (if (x != 0) ", envelope bits ${NumberText.hex(x)}" else "")
            FX_PATTERN_BREAK -> if (param == 0) "D00 (no effect)" else "Break to row ${param - 1} of the next order"
            FX_NOTE_CUT -> if (param == 0) "Cut immediately" else "Cut after $param ticks"
            FX_SET_SPEED -> "Speed $param ticks per row"
            else -> "Unknown effect"
        }
    }

    /** Result of planning the export-time jump that implements [Song.loopOrder]. */
    class LoopPlan(
        /** Replacement tracks for the final order position, by channel. Empty when no change is needed. */
        val tracks: Map<Int, List<Cell>>,
        val errors: List<String>,
    )

    /**
     * hUGEDriver wraps to order 0 after the last order. For [Song.loopOrder] L > 0, every way the final
     * order can fall through to "the next order" must carry B(L+1): B00 is rewritten, and the last row and
     * every row with a pattern break (Dxx) without an explicit Bxx get an injected B(L+1) in the first free
     * effect slot. Bxx and Dxx compose in the driver (B sets the order, D the row), so a D row keeps its
     * row target. A row without a free slot is a reported conflict; nothing is overwritten. [orderLabel] maps an
     * order position in [song] to the number its messages show (the document order when [song] is [arranged]).
     */
    fun loopPlan(song: Song, orderLabel: (Int) -> Int = { it }): LoopPlan {
        if (song.loopOrder == 0) return LoopPlan(emptyMap(), emptyList())
        val jump = song.loopOrder + 1
        val patternIndex = song.orders.last()
        val pattern = song.patterns[patternIndex]
        val channels = pattern.tracks.size
        val rows = pattern.length
        val edited = HashMap<Int, MutableList<Cell>>()
        val errors = ArrayList<String>()
        fun cell(ch: Int, row: Int) = edited[ch]?.get(row) ?: pattern.tracks[ch][row]
        fun set(ch: Int, row: Int, value: Cell) {
            edited.getOrPut(ch) { pattern.tracks[ch].toMutableList() }[row] = value
        }
        for (row in 0 until rows) {
            var breaks = row == rows - 1
            var explicitJump = false
            for (ch in 0 until channels) {
                val c = cell(ch, row)
                if (c.note == Cell.CUT) continue
                when {
                    c.effect == FX_POSITION_JUMP && c.param == 0 -> set(ch, row, c.copy(param = jump)).also { explicitJump = true }
                    c.effect == FX_POSITION_JUMP -> explicitJump = true
                    c.effect == FX_PATTERN_BREAK && c.param != 0 -> breaks = true
                }
            }
            if (!breaks || explicitJump) continue
            val free = (0 until channels).firstOrNull { ch -> cell(ch, row).let { it.note != Cell.CUT && !it.hasEffect } }
            if (free == null) {
                // Messages name orders as the user numbers them, so the suggested Bxx is the one to type.
                val typed = orderLabel(song.loopOrder) + 1
                errors += "Looping to order ${orderLabel(song.loopOrder)} needs a free effect slot on row $row of the last order " +
                    "(order ${orderLabel(song.orders.size - 1)}, ${Songs.patternLabel(song, patternIndex)}) for B${hex2(typed)}, " +
                    "but every channel uses its effect there; clear one effect on that row or enter B${hex2(typed)} yourself"
            } else {
                set(free, row, cell(free, row).copy(effect = FX_POSITION_JUMP, param = jump))
            }
        }
        return LoopPlan(edited, errors)
    }

    /**
     * The song as heard while looping the pattern at [order]: that order alone (hUGEDriver wraps back to order 0
     * after it), and every stored jump in it (Bxx, xx >= 01) aimed at that same single order, so a jump replays the
     * pattern instead of leaving it. B00 and Dxx already land on "the next order", which is the loop. Patterns not
     * in the loop are untouched. Editor playback only; never saved or exported.
     */
    fun patternLoop(song: Song, order: Int): Song {
        val index = song.orders[order.coerceIn(0, song.orders.size - 1)]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track ->
            track.map { if (it.effect == FX_POSITION_JUMP && it.param > 1) it.copy(param = 1) else it }
        }
        val patterns = if (tracks == pattern.tracks) song.patterns
        else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList())
    }

    /** The document order indices song playback and export use, in order: every order that is not parked. */
    fun playedOrders(song: Song): List<Int> {
        if (song.parkedOrders.isEmpty()) return song.orders.indices.toList()
        val parked = song.parkedOrders.toHashSet()
        return song.orders.indices.filter { it !in parked }
    }

    /**
     * The song as played and exported: its parked orders removed. Everything that names an order is renumbered to
     * the shorter list: the loop target and every stored `Bxx` (B01 = order 0; B00 is relative). A loop or jump
     * aimed at a parked order lands on the next played order after it (the loop target when none follows, and
     * the first played order for a parked loop target at the end). Returns [song] itself when nothing is parked or
     * everything is (which [SongValidator.errors] reports).
     */
    fun arranged(song: Song): Song {
        if (song.parkedOrders.isEmpty()) return song
        val played = playedOrders(song)
        if (played.isEmpty()) return song
        // Document order index → arranged index of the first played order at or after it (-1 past the last).
        val next = IntArray(song.orders.size + 1) { -1 }
        var k = played.size
        for (o in song.orders.indices.reversed()) {
            if (k > 0 && played[k - 1] == o) k--
            next[o] = if (k < played.size) k else -1
        }
        val loop = next[song.loopOrder].let { if (it >= 0) it else 0 }
        fun target(order: Int) = next[order].let { if (it >= 0) it else loop }
        val old = song.orders.size
        val patterns = song.patterns.map { p ->
            val tracks = p.tracks.map { track ->
                track.map { c -> if (c.effect == FX_POSITION_JUMP && c.param in 1..old) c.copy(param = target(c.param - 1) + 1) else c }
            }
            if (tracks == p.tracks) p else p.copy(tracks = tracks)
        }
        return song.copy(orders = played.map { song.orders[it] }, loopOrder = loop, patterns = patterns, parkedOrders = emptyList())
    }
}
