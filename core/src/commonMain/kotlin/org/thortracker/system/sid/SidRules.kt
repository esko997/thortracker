package org.thortracker.system.sid

import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec

/**
 * C64 SID cell semantics: GoatTracker 2's pattern commands 1-F with the same numbers, one per row. Parameters are values,
 * not table rows: GoatTracker's speedtable commands (1-4, E) take the parameters GoatTracker 1.x took, which
 * GoatTracker 2 documents as their speedtable equivalents (portamento `xx` = speed 4·xx, vibrato `xy` = speed x and
 * depth y·`$10`, funktempo `xy` = tempos x and y), and the lowering puts each distinct value in a speedtable row
 * ([SidSpeeds]); the program commands (8-A) name an instrument whose program to start. No jumps or pattern breaks:
 * orders arrange the song. Channel tempos (`F83-FFF`) are not offered, so all three voices keep one row clock.
 */
object SidRules {
    /** GoatTracker's pattern range: C-0..G#7 (its frequency table, and wave programs, reach B-7). */
    val NOTES = 0..92

    /** GoatTracker's "key on" (`+++`): sets the gate again without restarting the note. A SID-only note value. */
    const val KEY_ON = -3

    /** Ticks per row GoatTracker can play for the whole song (`F03-F7F`, default tempo). */
    val TEMPOS = 3..127

    /** Funktempo tempos per row step (`Exy` digits). */
    val FUNK_TEMPOS = 2..15

    val CHANNEL_NAMES = listOf("Voice 1", "Voice 2", "Voice 3")

    const val FX_PORTA_UP = 1
    const val FX_PORTA_DOWN = 2
    const val FX_TONE_PORTA = 3
    const val FX_VIBRATO = 4
    const val FX_ATTACK_DECAY = 5
    const val FX_SUSTAIN_RELEASE = 6
    const val FX_WAVEFORM = 7
    const val FX_WAVE_PROGRAM = 8
    const val FX_PULSE_PROGRAM = 9
    const val FX_FILTER_PROGRAM = 10 // A
    const val FX_FILTER_CONTROL = 11 // B
    const val FX_CUTOFF = 12 // C
    const val FX_VOLUME = 13 // D
    const val FX_FUNKTEMPO = 14 // E
    const val FX_TEMPO = 15 // F

    /** The table each program command starts a program in. */
    fun programTable(effect: Int): Int = when (effect) {
        FX_WAVE_PROGRAM -> SidTables.WAVE
        FX_PULSE_PROGRAM -> SidTables.PULSE
        FX_FILTER_PROGRAM -> SidTables.FILTER
        else -> -1
    }

    val EFFECTS = listOf(
        EffectSpec(FX_PORTA_UP, "Portamento up", "xx: speed 01-FF (×4 per tick)",
            "Slide the pitch up by 4·xx frequency units every tick from this row until another command 1-4 or 000 " +
                "(100 holds the pitch).", true),
        EffectSpec(FX_PORTA_DOWN, "Portamento down", "xx: speed 01-FF (×4 per tick)",
            "Slide the pitch down by 4·xx frequency units every tick from this row until another command 1-4 or 000.", true),
        EffectSpec(FX_TONE_PORTA, "Toneportamento", "xx: speed 01-FF (×4 per tick), 00 = tie",
            "Glide from the playing pitch to this row's note at 4·xx frequency units per tick without restarting it; " +
                "300 ties the note (moves at once, no new attack).", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x=speed 1-F, y=depth 1-F",
            "Wobble the pitch: it turns every x ticks and moves y·10 frequency units per tick. Lasts until another " +
                "command 1-4 or 000 (then the instrument's own vibrato returns); 400 stops it.", true),
        EffectSpec(FX_ATTACK_DECAY, "Attack/decay", "xy: attack x, decay y",
            "Set the voice's attack and decay (0 fast - F slow) from this row on.", true),
        EffectSpec(FX_SUSTAIN_RELEASE, "Sustain/release", "xy: sustain x, release y",
            "Set the voice's sustain level (0-F) and release (0 fast - F slow) from this row on.", true),
        EffectSpec(FX_WAVEFORM, "Waveform", "xx: waveform register",
            "Set the waveform register (10 tri, 20 saw, 40 pulse, 80 noise, +1 gate, +2 sync, +4 ring, +8 test). " +
                "A running wave program that sets waveforms overrides it.", true),
        EffectSpec(FX_WAVE_PROGRAM, "Wave program", "xx: instrument 01-3F, 00 = stop",
            "Run instrument xx's wave program on this voice (arpeggio, drum, waveform change); 800 stops the wave program.", true),
        EffectSpec(FX_PULSE_PROGRAM, "Pulse program", "xx: instrument 01-3F, 00 = stop",
            "Run instrument xx's pulse program on this voice; 900 stops pulse modulation.", true),
        EffectSpec(FX_FILTER_PROGRAM, "Filter program", "xx: instrument 01-3F, 00 = stop",
            "Run instrument xx's filter program: this voice takes over the chip's one filter; A00 stops filter changes.", true),
        EffectSpec(FX_FILTER_CONTROL, "Filter control", "xy: x=resonance, y=voices (1+2+4)",
            "Set the filter resonance and which voices go through it (bit 1 = voice 1, 2 = voice 2, 4 = voice 3). " +
                "B00 turns the filter off and stops the filter program.", true),
        EffectSpec(FX_CUTOFF, "Filter cutoff", "xx: 00-FF",
            "Set the filter cutoff (a running filter program that changes the cutoff overrides it).", true),
        EffectSpec(FX_VOLUME, "Master volume", "xx: 00-0F",
            "Set the chip's master volume (0-F) for all voices; it stays until changed, so set it at the song start " +
                "if the song changes it.", true),
        EffectSpec(FX_FUNKTEMPO, "Funktempo", "xy: tempos x and y (2-F)",
            "Alternate rows of x and y ticks (swing) on all voices. Fxx sets a plain tempo again; F00/F01 resume the swing.", true),
        EffectSpec(FX_TEMPO, "Tempo", "xx: ticks per row 03-7F; 00/01 resume Exy",
            "Ticks per row from this row on, all voices. F00 and F01 return to the last funktempo (starting with its " +
                "first or second tempo).", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    fun hex2(value: Int) = NumberText.hex(value, 2)

    fun effectChar(code: Int): Char = EffectSpec.charOf(code)

    fun effectText(effect: Int, param: Int) = "${effectChar(effect)}${hex2(param)}"

    /**
     * The speedtable row value (left shl 8 or right) effect [effect] with [param] needs, or -1 when it uses row 0 (no
     * speed: `100`, `200`, `300` tie, `400` off) or no speedtable row at all.
     */
    fun speedOf(effect: Int, param: Int): Int = when (effect) {
        FX_PORTA_UP, FX_PORTA_DOWN, FX_TONE_PORTA -> if (param == 0) -1 else param * 4
        FX_VIBRATO -> if (param == 0) -1 else ((param shr 4) shl 8) or ((param and 0x0F) shl 4)
        FX_FUNKTEMPO -> ((param shr 4) shl 8) or (param and 0x0F)
        else -> -1
    }

    /** Why parameter [param] of effect [effect] (on a pattern row or a wave program command) cannot be played, or null. */
    fun paramProblem(effect: Int, param: Int): String? {
        val x = param shr 4
        val y = param and 0x0F
        val fx = effectText(effect, param)
        return when (effect) {
            0 -> if (param != 0) "$fx: command 0 has no parameter (000 = no command)" else null
            FX_VIBRATO -> if (param != 0 && (x == 0 || y == 0)) "$fx has speed $x and depth $y; set both digits (4xy, 1-F) or use 400 to stop" else null
            FX_WAVE_PROGRAM, FX_PULSE_PROGRAM, FX_FILTER_PROGRAM ->
                if (param > SidSystem.SLOTS) "$fx: instruments are 01-${hex2(SidSystem.SLOTS)} (00 = stop)" else null
            FX_VOLUME -> if (param > 0x0F) "$fx: master volumes are D00-D0F" else null
            FX_FUNKTEMPO -> if (x !in FUNK_TEMPOS || y !in FUNK_TEMPOS) "$fx: funktempo digits are 2-F ticks each" else null
            FX_TEMPO -> when {
                param >= 0x80 -> "$fx: channel tempos (F83-FFF) are not offered; all voices share one tempo (F03-F7F)"
                param == 2 -> "F02: tempos are F03-F7F (F00/F01 resume a funktempo)"
                else -> null
            }
            else -> null
        }
    }

    /** Range problem of a single cell independent of context, or null. */
    fun cellRangeProblem(cell: Cell): String? = when {
        cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note != KEY_ON && cell.note !in NOTES ->
            "note ${cell.note} is outside C-0..G#7 (0-92), GoatTracker's pattern range"
        cell.instrument !in 0..SidSystem.SLOTS -> "instrument ${cell.instrument} is outside 0-${SidSystem.SLOTS}"
        cell.effect !in 0..EffectSpec.MAX_CODE -> "effect ${cell.effect} is outside 0-Z"
        cell.param !in 0..255 -> "effect parameter ${cell.param} is outside 00-FF"
        cell.more.isNotEmpty() -> "${cell.more.size + 1} effect columns; the SID plays one per row"
        cell.hasEffect && cell.effect != 0 && effect(cell.effect) == null -> "effect ${effectChar(cell.effect)} does not exist on the SID"
        else -> null
    }

    /** The first reason [cell] cannot be played/exported, or null. Assumes [cellRangeProblem] is null. */
    fun cellProblem(song: Song, cell: Cell): String? {
        paramProblem(cell.effect, cell.param)?.let { return it }
        val table = programTable(cell.effect)
        if (table >= 0 && cell.param > 0 && song.sid.instruments[cell.param - 1].program(table).rows.isEmpty()) {
            return "${effectText(cell.effect, cell.param)}: instrument ${hex2(cell.param)} has no ${SidTables.NAMES[table].lowercase()} program " +
                "(${effectChar(cell.effect)}00 stops the program)"
        }
        if (cell.effect == FX_TONE_PORTA && cell.note < 0) return "${effectText(cell.effect, cell.param)} needs a note to glide to on its row"
        return null
    }

    fun describe(effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        return when (effect) {
            0 -> "No command (instrument vibrato)"
            FX_PORTA_UP -> if (param == 0) "Portamento stopped" else "Portamento up, speed ${NumberText.hex(param * 4, 3)}"
            FX_PORTA_DOWN -> if (param == 0) "Portamento stopped" else "Portamento down, speed ${NumberText.hex(param * 4, 3)}"
            FX_TONE_PORTA -> if (param == 0) "Tie to the note" else "Glide to the note, speed ${NumberText.hex(param * 4, 3)}"
            FX_VIBRATO -> if (param == 0) "Vibrato off" else "Vibrato speed $x, depth ${hex2(y * 16)}"
            FX_ATTACK_DECAY -> "Attack ${NumberText.hex(x)}, decay ${NumberText.hex(y)}"
            FX_SUSTAIN_RELEASE -> "Sustain ${NumberText.hex(x)}, release ${NumberText.hex(y)}"
            FX_WAVEFORM -> "Waveform ${SidTables.waveformName(param)}"
            FX_WAVE_PROGRAM, FX_PULSE_PROGRAM, FX_FILTER_PROGRAM -> {
                val name = SidTables.NAMES[programTable(effect)].lowercase()
                if (param == 0) "Stop the $name program" else "Run instrument ${hex2(param)}'s $name program"
            }
            FX_FILTER_CONTROL -> if (param == 0) "Filter off" else "Filter resonance ${NumberText.hex(x)}, ${SidTables.routingName(y)}"
            FX_CUTOFF -> "Filter cutoff ${hex2(param)}"
            FX_VOLUME -> "Master volume ${NumberText.hex(param and 0x0F)}"
            FX_FUNKTEMPO -> "Funktempo $x/$y ticks per row"
            FX_TEMPO -> when (param) {
                0 -> "Resume funktempo (first tempo)"
                1 -> "Resume funktempo (second tempo)"
                else -> "Tempo $param ticks per row"
            }
            else -> "Unknown effect"
        }
    }
}
