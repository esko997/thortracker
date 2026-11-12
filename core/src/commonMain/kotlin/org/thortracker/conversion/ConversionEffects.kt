package org.thortracker.conversion

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.model.Cell
import org.thortracker.model.Fx
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.cbm.CbmSystem
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.gba.GbaSystem
import org.thortracker.system.nes.NesFamiStudioSystem
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opm.OpmSystem
import org.thortracker.system.opn.OpnFamilySystem
import org.thortracker.system.opn.OpnSystem
import org.thortracker.system.opn.OpnaSystem
import org.thortracker.system.pce.PceSystem
import org.thortracker.system.saa.Saa1099System
import org.thortracker.system.scc.SccSystem
import org.thortracker.system.scc.SccPlusSystem
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.sid.SidSound
import org.thortracker.system.sms.SmsSystem
import org.thortracker.system.vsu.VsuSystem
import org.thortracker.system.ws.WsSystem
import org.thortracker.system.ws.WscSystem
import org.thortracker.system.ym2612.YmSystem

/** Converts operations, not similarly named driver bytes. No claim of acoustic equivalence. */
internal object ConversionEffects {
    private enum class Driver { GB, NES, SID, SMS, XM, NATIVE }
    private fun driver(system: SoundSystem): Driver = when (system) {
        GbSystem -> Driver.GB
        is NesFamiStudioSystem -> Driver.NES
        SidSystem -> Driver.SID
        is SmsSystem -> Driver.SMS
        GbaSystem -> Driver.XM
        else -> Driver.NATIVE
    }

    private fun speedRange(system: SoundSystem): IntRange = when (driver(system)) {
        Driver.NES, Driver.XM -> 1..31
        Driver.SID -> 3..127
        else -> 1..255
    }

    private fun tickHz(song: Song): Double {
        if (song.system == GbSystem) return 4194304.0 / 70224.0
        if (song.system is NesFamiStudioSystem) return 1789773.0 / 29780.5
        val sid = song.sound as? SidSound ?: return song.system.ticksPerSecond(song)
        return if (sid.multispeed == 1) 985248.0 / 19656.0
        else 985248.0 / (0x4cc7 / sid.multispeed + 1)
    }

    fun speed(source: Song, destination: Song, issues: MutableList<ConversionIssue>): Int {
        val speed = source.ticksPerRow.coerceIn(speedRange(destination.system))
        if (speed != source.ticksPerRow) issues += ConversionIssue(ConversionImpact.CHANGE, "Speed",
            "${source.ticksPerRow} ticks/row fitted to $speed; ${destination.system.name} cannot represent the original speed.")
        val from = tickHz(source)
        val to = tickHz(destination)
        if (abs(from - to) > 0.000001) issues += ConversionIssue(ConversionImpact.CHANGE, "Clock",
            "Tick rate changes from $from Hz to $to Hz. Tick counts are retained where legal, not wall-clock timing, envelopes or modulation rate.")
        if (driver(source.system) != driver(destination.system)) {
            issues += ConversionIssue(ConversionImpact.CHANGE, "Sequencing",
                "Driver timing changes: native loop-entry speed restoration, legacy flow and effect update phases are not audio-equivalent.")
        }
        return speed
    }

    private data class Candidate(val fx: Fx, val column: Int, val priority: Int)

    fun cell(source: Song, destination: Song, lane: ConversionLane, pattern: Int, row: Int,
             original: Cell, mapped: Cell, issues: MutableList<ConversionIssue>): Cell {
        val from = driver(source.system)
        val to = driver(destination.system)
        if (!original.hasAnyEffect && !(original.note == Cell.CUT && from != to)) return mapped.withoutEffects()
        val location = "Pattern ${pattern + 1}, channel ${lane.source + 1}, row $row"
        fun issue(column: Int, detail: String, impact: ConversionImpact = ConversionImpact.LOSS) {
            issues += ConversionIssue(impact, "$location, effect ${column + 1}", detail)
        }
        if (compatiblePayload(source, destination, lane))
            return mapped.copy(effect = original.effect, param = original.param, more = original.more)
        val candidates = mutableListOf<Candidate>()
        var result = mapped.withoutEffects()
        if (original.note == Cell.CUT && from != to) issues += ConversionIssue(ConversionImpact.CHANGE,
            "$location, note", "OFF is retained; immediate silence versus envelope release follows the destination driver/instrument.")
        val delayCode = if (from == Driver.GB) 7 else 16
        val delay = lastParam(original, delayCode) ?: 0
        val sourceEffects = source.system.effectsFor(lane.source)
        fun add(code: Int, param: Int, column: Int) {
            candidates += Candidate(Fx(code, param), column, when (code) {
                11, 13 -> 0
                15 -> 1
                14, 28 -> 2
                7, 16 -> 3
                else -> 4
            })
        }
        fun changed(column: Int, detail: String) = issue(column, detail, ConversionImpact.CHANGE)
        for (column in 0..original.lastEffectColumn) {
            val code = original.effectCode(column)
            val param = original.effectParam(column)
            if (code == 0 && param == 0) continue // 000 is omission, not an arpeggio-clear command.
            val text = "${org.thortracker.system.EffectSpec.charOf(code)}${param.toString(16).uppercase().padStart(2, '0')}"
            if (sourceEffects.none { it.code == code && it.supported }) {
                issue(column, "$text omitted: the source driver does not support this operation on this channel.")
                continue
            }
            val replacesEarlier = (from == Driver.NATIVE && (code in 0..4 || code == 10 || code == 12 ||
                code == 16 || code == 25 || code == 28)) ||
                (from == Driver.SMS && (code == 12 || code == 16 || code == 24 || code == 28)) ||
                from == Driver.NES
            if (replacesEarlier && (column + 1..original.lastEffectColumn).any {
                    val later = original.effectCode(it)
                    (when {
                        from == Driver.NES && (code == 10 || code == 12) -> later == 10 || later == 12
                        from == Driver.NES && (code == 26 || code == 27) -> later == 26 || later == 27
                        code in 1..3 -> later in 1..3
                        else -> later == code
                    }) &&
                        (later != 0 || original.effectParam(it) != 0)
                }) {
                changed(column, "$text omitted because a later source command replaces this row's setting.")
                continue
            }
            if (from == Driver.SMS && to == Driver.SMS &&
                (code in 0..4 || code == 12 || code == 16 || code == 24 || code == 28)) {
                add(code, param, column)
                continue
            }
            // SID B/C/D/E are filter control, cutoff, MASTER volume and funktempo, never flow/cut.
            if (from == Driver.SID && code != 15) {
                if (code == 13 && destination.system is CbmSystem) {
                    val max = if (destination.system.channels.size == 4) 15 else 8
                    add(31, (param * max / 15.0).roundToInt(), column)
                    changed(column, "$text master volume becomes V; shared envelope ownership and gain curve change.")
                } else issue(column, "$text omitted: SID frequency/table/filter/funktempo commands have no compatible destination operation (300 is a tie, not slide-off).")
                continue
            }
            if (code == 15) {
                if (from == Driver.XM && param >= 32) {
                    issue(column, "$text omitted: XM BPM changes the tick clock, not the number of ticks per row.")
                    continue
                }
                if (from == Driver.SID && param < 3) {
                    issue(column, "$text resumes funktempo; no fixed-speed equivalent.")
                    continue
                }
                if (!winningSpeed(source, pattern, row, lane.source, column)) {
                    changed(column, "$text omitted because another source speed command wins on this row.")
                    continue
                }
                val value = param.coerceIn(speedRange(destination.system))
                add(15, value, column)
                if (value != param) changed(column, "$text fitted to F${value.toString(16).uppercase()}; row tick count changes.")
                if ((from == Driver.SMS) != (to == Driver.SMS)) changed(column,
                    "$text changes application timing: Banjo F takes effect next row; the other driver uses the current row.")
                continue
            }
            if (from == Driver.SMS && code == 24) {
                issue(column, "$text odd-row speed/swing omitted: the destination has no equivalent alternating-speed command.")
                continue
            }
            if (from == Driver.NES && (code == 26 || code == 27)) {
                issue(column, "$text omitted: NES slides encode a destination and derive duration from future notes/cuts; they are not fixed per-tick pitch rates.")
                continue
            }
            if (to == Driver.SID) {
                if (source.system is CbmSystem && code == 31) {
                    val max = if (source.system.channels.size == 4) 15 else 8
                    add(13, (param * 15.0 / max).roundToInt(), column)
                    changed(column, "$text becomes SID D master volume; shared envelope ownership and gain curve change.")
                } else issue(column, "$text omitted: SID has no corresponding portable cell command.")
                continue
            }
            if (code == 11 || code == 13) {
                if ((from == Driver.NATIVE || from == Driver.GB || from == Driver.XM) && !winningGlobal(source, pattern, row, lane.source, column, code)) {
                    changed(column, "$text omitted because a later source flow command wins on this row.")
                    continue
                }
                if (from == Driver.SMS && ((code == 13 && lastParam(original, 11) != null) ||
                    (code == 11 && (0 until column).any { original.effectCode(it) == 11 }))) {
                    changed(column, "$text omitted: Banjo uses the first B in a cell and ignores D beside B.")
                    continue
                }
                if (from == Driver.GB && code == 11 && param == 0 &&
                    source.patterns[pattern].tracks.any { it[row].effect == 13 }) {
                    changed(column, "$text next-order request is represented by the source row's D command.")
                    continue
                }
                if (to == Driver.NES) {
                    issue(column, "$text omitted: FamiStudio has no order jump or pattern break command.")
                    continue
                }
                var outCode = code
                var value = param
                if (from == Driver.GB) {
                    if (code == 11 && param == 0) { outCode = 13; value = 0 }
                    else value = param - 1
                }
                if (from == Driver.XM && code == 13) value = (param shr 4) * 10 + (param and 15)
                if (to == Driver.XM && outCode == 13) {
                    if (value !in 0..99) {
                        issue(column, "$text omitted: XM pattern-break rows must fit two decimal digits.")
                        continue
                    }
                    value = ((value / 10) shl 4) or (value % 10)
                }
                if (to == Driver.GB) value += 1
                if (to == Driver.SMS && outCode == 13 && value != 0) {
                    issue(column, "$text omitted: Banjo only breaks to row zero, not row $value.")
                } else if (value !in 0..255 || (to == Driver.GB && outCode == 13 && value > 64)) {
                    issue(column, "$text omitted: destination flow address is not representable.")
                } else {
                    add(outCode, value, column)
                    if (from == Driver.GB || to == Driver.GB) changed(column, "$text translated between GB one-based and destination/source zero-based flow addresses.")
                    if (code == 13 && (from == Driver.XM || to == Driver.XM)) changed(column, "$text translated between XM decimal-digit row encoding and the other driver's row address.")
                    if (from == Driver.SMS || to == Driver.SMS) changed(column, "$text uses Banjo first-B/ B-over-D precedence rather than native last-column flow precedence.")
                }
                continue
            }
            if ((from == Driver.GB && code == 7) || (from != Driver.GB && code == 16)) {
                if (param == 0) {
                    if (from == Driver.GB) {
                        result = result.copy(note = Cell.EMPTY, instrument = 0)
                        changed(column, "GB 700 never triggers its note; the untriggered note and instrument were omitted rather than played immediately.")
                    }
                    continue
                }
                if (to == Driver.NES && (param > 31 || mapped.note == Cell.EMPTY)) {
                    issue(column, "$text omitted: NES delay needs a note/OFF and 1–31 frames.")
                } else {
                    add(if (to == Driver.GB) 7 else 16, param, column)
                    if (from == Driver.GB || to == Driver.GB || from == Driver.NES || to == Driver.NES) changed(column,
                        "$text preserves delay ticks, but GB/NES delay the note whereas native/Banjo delay row commands.")
                }
                continue
            }
            if ((from == Driver.GB && code == 14) || (from != Driver.GB && code == 28)) {
                if (from == Driver.NATIVE && to != Driver.NATIVE && param < delay) {
                    changed(column, "$text omitted: the absolute cut tick precedes installation by G and never fires in the source row.")
                    continue
                }
                var value = param
                if (from == Driver.SMS) value += delay // Banjo waits again after its delayed attack.
                if (to == Driver.SMS) value -= delay
                if (value !in 0..255 || (to == Driver.NES && value > 31)) {
                    issue(column, "$text omitted: translated cut tick $value is outside destination range.")
                } else if (to == Driver.SMS && value == 0) {
                    if (original.note >= 0) changed(column, "$text cuts at the attack tick; the zero-duration attack is replaced by OFF.")
                    result = result.copy(note = Cell.CUT, instrument = 0)
                } else {
                    add(if (to == Driver.GB) 14 else 28, value, column)
                }
                if (from != to) changed(column,
                    "$text retains cut scheduling where representable; GB/NES silence immediately, native OFF may release an envelope, and Banjo G+S waits are additive.")
                continue
            }
            when (code) {
                0 -> {
                    if (to == Driver.NES && original.note < 0) issue(column, "$text omitted: NES arpeggio requires a note on this row.")
                    else {
                        add(0, param, column)
                        if (from != to) changed(column, "$text keeps semitone offsets, not driver arpeggio phase, persistence or instrument-arpeggio interaction.")
                    }
                }
                1, 2, 3 -> when {
                    from == Driver.NATIVE && to == Driver.NATIVE -> {
                        add(code, param, column)
                        changed(column, "$text keeps nominal 1/64-semitone/tick movement; pitch quantization and held/released portamento eligibility can change.")
                    }
                    (from == Driver.NATIVE && to == Driver.SMS) || (from == Driver.SMS && to == Driver.NATIVE) -> {
                        val value = if (from == Driver.SMS) param * 2 else (param / 2.0).roundToInt()
                        add(code, value.coerceIn(0, 255), column)
                        changed(column, "$text converts 1/64 versus 1/32-semitone slide rate; rounding/saturation, Banjo's single pitch-effect slot and portamento persistence can change movement.")
                    }
                    else -> issue(column, "$text omitted: period/frequency or destination-and-duration slides are not semitone-rate commands.")
                }
                4, 25 -> if (from == Driver.NATIVE && to == Driver.NATIVE) {
                    add(code, param, column)
                    changed(column, "$text retains nominal native pitch units; chip quantization and noise/sample applicability may change.")
                } else issue(column, "$text omitted: driver vibrato/fine-pitch units, phase and lifecycle differ.")
                12 -> {
                    val sourceMax = volumeMax(source.system, lane.source)
                    val targetMax = volumeMax(destination.system, lane.target)
                    if (sourceMax == null || targetMax == null) issue(column, "$text omitted: local volume must not become filter cutoff or shared master volume.")
                    else {
                        val level = if (from == Driver.GB) param and 15 else param
                        val value = (level.toDouble() * targetMax / sourceMax).roundToInt().coerceIn(0, targetMax)
                        add(12, value, column)
                        changed(column, "$text level mapped from 0–$sourceMax to 0–$targetMax; attenuation/envelope curves, GB retrigger/quantization and VSU envelope-level locks are not equivalent.")
                    }
                }
                10 -> {
                    val sourceMax = volumeMax(source.system, lane.source)
                    val targetMax = volumeMax(destination.system, lane.target)
                    if (from == Driver.NATIVE && to == Driver.NATIVE && sourceMax != null && targetMax != null &&
                        source.system != VsuSystem && destination.system != VsuSystem) {
                        val ratio = targetMax.toDouble() / sourceMax * slideDivisor(destination.system) / slideDivisor(source.system)
                        fun nibble(n: Int): Int = if (n == 0) 0 else (n * ratio).roundToInt().coerceIn(1, 15)
                        add(10, (nibble(param shr 4) shl 4) or nibble(param and 15), column)
                        changed(column, "$text volume-slide rate scaled and fitted to destination level steps; envelope/gain curve and saturation change.")
                    } else issue(column, "$text omitted: native rate, NES endpoint interpolation, GB retrigger and CBM global fade are different operations.")
                }
                else -> issue(column, "$text omitted: hardware/resource command has no source-backed portable translation.")
            }
        }
        // GB OFF lowers to E00 and consumes the only effect slot: never let another effect erase it.
        if (to == Driver.GB && result.note == Cell.CUT) {
            candidates.forEach { issue(it.column, "Effect omitted: GB OFF requires the sole effect slot for E00.") }
            return result.copy(instrument = 0)
        }
        val capacity = destination.system.effectColumns(lane.target)
        val targetEffects = destination.system.effectsFor(lane.target)
        val selected = mutableListOf<Candidate>()
        for (candidate in candidates.sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.column })) {
            val fx = candidate.fx
            if (targetEffects.none { it.code == fx.effect && it.supported }) {
                issue(candidate.column, "Translated command ${fx.effect} omitted: unsupported on destination channel ${lane.target + 1}.")
                continue
            }
            if (selected.size == capacity) {
                issue(candidate.column, "Translated command omitted: destination channel ${lane.target + 1} has $capacity effect column(s); flow, speed, cut and delay take priority.")
                continue
            }
            val trial = result.withEffect(selected.size, fx.effect, fx.param)
            val problem = destination.system.cellProblem(destination, lane.target, trial)
            if (problem != null) issue(candidate.column, "Translated command omitted: $problem")
            else { selected += candidate; result = trial }
        }
        // Preserve source column order for the accepted commands; priority chooses omissions, not execution order.
        result = result.withoutEffects()
        selected.sortedBy { it.column }.forEachIndexed { index, candidate ->
            result = result.withEffect(index, candidate.fx.effect, candidate.fx.param)
        }
        return result
    }

    private fun lastParam(cell: Cell, code: Int): Int? {
        for (column in cell.lastEffectColumn downTo 0) if (cell.effectCode(column) == code) return cell.effectParam(column)
        return null
    }

    /** Only inspect this row, not a whole-song scan for every cell. */
    private fun winningSpeed(song: Song, pattern: Int, row: Int, channel: Int, column: Int): Boolean =
        winningGlobal(song, pattern, row, channel, column, 15)

    private fun winningGlobal(song: Song, pattern: Int, row: Int, channel: Int, column: Int, code: Int): Boolean {
        val tracks = song.patterns[pattern].tracks
        var winnerChannel = -1
        var winnerColumn = -1
        for (ch in tracks.indices) {
            val cell = tracks[ch][row]
            for (col in 0..cell.lastEffectColumn) if (cell.effectCode(col) == code) {
                if (code == 15 && driver(song.system) == Driver.NES && winnerChannel >= 0 && winnerChannel != ch) continue
                winnerChannel = ch
                winnerColumn = col
            }
        }
        return channel == winnerChannel && column == winnerColumn
    }

    /** Payload preservation is deliberately limited to the voice adapter's safe promotions. */
    private fun compatiblePayload(source: Song, destination: Song, lane: ConversionLane): Boolean {
        val a = source.system
        val b = destination.system
        if (a == b && lane.source == lane.target) return true
        return when {
            a == NesSystem && b is NesFamiStudioSystem -> lane.source < 5 && lane.source == lane.target
            a == WsSystem && b == WscSystem -> lane.source == lane.target && lane.source < 4
            a == OpnSystem && b == OpnaSystem -> if (lane.source < 3) lane.target == lane.source else lane.target == lane.source + 3
            a == SmsSystem.PSG && b is SmsSystem -> lane.source < 4 && lane.source == lane.target
            else -> false
        }
    }

    private fun volumeMax(system: SoundSystem, channel: Int): Int? {
        if (system is CbmSystem || system == SidSystem) return null
        if (system.effectsFor(channel).none { it.code == 12 && it.supported }) return null
        return when (system) {
            Opl3System -> 63
            GbaSystem -> 64
            OpmSystem -> 127
            YmSystem -> if (channel < 6) 127 else 15
            is OpnFamilySystem -> when (system.channels[channel].bank) { 0 -> 127; 1 -> 15; 2 -> 31; else -> 255 }
            PceSystem -> 31
            else -> 15
        }
    }

    private fun slideDivisor(system: SoundSystem): Int =
        if (system == SccSystem || system == SccPlusSystem || system is Saa1099System) 16 else 8
}
