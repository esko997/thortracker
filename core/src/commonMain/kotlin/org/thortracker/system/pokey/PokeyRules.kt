package org.thortracker.system.pokey

import org.thortracker.backend.PokeyPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec

/**
 * POKEY cell semantics: Thor Tracker's own player (docs/AUDIO_ENGINE.md, "POKEY: Effects"), up to [EFFECT_COLUMNS]
 * effect columns per channel, the OPL3's letters where meanings match. `Hxx` and `Vxx` shape the note of their row
 * (or the sounding voice); `B D F K N R T W` are global and act at the row's tick 0.
 */
object PokeyRules {
    /** Absolute notes the player takes: C-0..B-8 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES = 0 until PokeyPlayerImage.NOTES

    const val EFFECT_COLUMNS = PokeyPlayerImage.MAX_EFFECTS

    val CHANNEL_NAMES = (1..PokeyPlayerImage.CHANNELS).map { "POKEY $it" }

    const val FX_ARPEGGIO = PokeyPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = PokeyPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = PokeyPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = PokeyPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = PokeyPlayerImage.FX_VIBRATO
    const val FX_VOLUME_SLIDE = PokeyPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = PokeyPlayerImage.FX_JUMP
    const val FX_VOLUME = PokeyPlayerImage.FX_VOLUME
    const val FX_BREAK = PokeyPlayerImage.FX_BREAK
    const val FX_SPEED = PokeyPlayerImage.FX_SPEED
    const val FX_DELAY = PokeyPlayerImage.FX_DELAY
    const val FX_FILTER = PokeyPlayerImage.FX_FILTER
    const val FX_CLOCK = PokeyPlayerImage.FX_CLOCK
    const val FX_POLY = PokeyPlayerImage.FX_POLY
    const val FX_FINE = PokeyPlayerImage.FX_FINE
    const val FX_RESTART = PokeyPlayerImage.FX_RESTART
    const val FX_CUT = PokeyPlayerImage.FX_CUT
    const val FX_TIMERS = PokeyPlayerImage.FX_TIMERS
    const val FX_DISTORTION = PokeyPlayerImage.FX_DISTORTION
    const val FX_TWO_TONE = PokeyPlayerImage.FX_TWO_TONE

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = PokeyPlayerImage.FINE_CENTRE

    /** Effects acting on the whole chip at the row's tick 0 (not delayed by `Gxx`). */
    val GLOBAL_EFFECTS = setOf(FX_JUMP, FX_BREAK, FX_SPEED, FX_CLOCK, FX_POLY, FX_RESTART, FX_TIMERS, FX_TWO_TONE)

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note); the next note plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding, unreleased voice glides there without a new attack; 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "Wobble the pitch (depth F: ±1.9 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the channel volume every tick until A00.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Channel volume from this row on (start 0F); scales the volume envelope and sample levels.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and its own effects) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_FILTER, "High-pass offset", "xx: signed (difference sounds 01-7F)",
            "The high-pass clock channel's counter = the sound's + xx (80-FF are −128..−1) for the note on this row; " +
                "small offsets phase, difference sounds play the rate difference (clamped to 01-7F).", true),
        EffectSpec(FX_CLOCK, "Base clock", "xx: 00 64 kHz, 01 15 kHz",
            "Global: every base-clocked counter counts the 64 kHz or 15 kHz clock; same pitches, coarser on 15 kHz.", true),
        EffectSpec(FX_POLY, "Noise generator", "xx: 00 17-bit, 01 9-bit",
            "Global: distortions 0 and 8 sample the 17-bit (hiss) or 9-bit (buzzy, pitched) generator.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_RESTART, "Generator restart", "00",
            "Global: an initialization-mode pulse; the noise generators and base clocks restart, so noise repeats exactly.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Release at tick xx of this row, like an OFF at that tick.", true),
        EffectSpec(FX_TIMERS, "Timer restart", "00",
            "Global: STIMER; every counter reloads and every output goes high (aligns high-pass and sample phases).", true),
        EffectSpec(FX_DISTORTION, "Distortion", "xx: 00-08",
            "Override the distortion envelope for the note on this row: 00-07 = AUDC 0 2 4 6 8 A C E, 08 = volume only.", true),
        EffectSpec(FX_TWO_TONE, "Two-tone", "xx: 00 off, 01 on, 02 sync",
            "Global: POKEY 1 and 2 resync each other (01), or only POKEY 2 restarts POKEY 1 (02, a hard-sync sound).", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load (the checks `pk_load_image` makes), given the played order count; or null. */
    fun paramProblem(code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > 0x0F) "$fx: volumes are C00-C0F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_CLOCK -> if (param > 1) "$fx: the base clock is K00 (64 kHz) or K01 (15 kHz)" else null
            FX_POLY -> if (param > 1) "$fx: the noise generator is N00 (17-bit) or N01 (9-bit)" else null
            FX_RESTART -> if (param != 0) "$fx: the generator restart is R00" else null
            FX_TIMERS -> if (param != 0) "$fx: the timer restart is T00" else null
            FX_DISTORTION -> if (param > 8) "$fx: distortions are V00-V08" else null
            FX_TWO_TONE -> if (param > 2) "$fx: two-tone is W00, W01 or W02" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) return "note ${cell.note} is outside C-0..B-8 (0-107)"
        if (cell.instrument !in 0..PokeySystem.SLOTS) return "instrument ${cell.instrument} is outside 0-${PokeySystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the POKEY plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the POKEY"
        }
        return null
    }

    /** Human-readable meaning of [effect]/[param] for the inspector and picker. */
    fun describe(@Suppress("UNUSED_PARAMETER") song: Song?, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> if (param > 0x0F) "Volume $hex (out of range: 00-0F)" else "Volume $param of 15"
            FX_BREAK -> "Break to row $param of the next order"
            FX_SPEED -> if (param == 0) "Speed 0 (not allowed)" else "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_FILTER -> {
                val s = if (param >= 0x80) param - 256 else param
                "High-pass offset ${signed(s)} for this note"
            }
            FX_CLOCK -> when (param) { 0 -> "Base clock 64 kHz (all channels)"; 1 -> "Base clock 15 kHz (all channels)"; else -> "Base clock: K00 or K01" }
            FX_POLY -> when (param) { 0 -> "Noise from the 17-bit generator"; 1 -> "Noise from the 9-bit generator"; else -> "Noise generator: N00 or N01" }
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_RESTART -> "Restart the noise generators and base clocks"
            FX_CUT -> "Note off at tick $param"
            FX_TIMERS -> "Restart every timer (STIMER)"
            FX_DISTORTION -> if (param in PokeyDistortion.RANGE) "Distortion ${PokeyDistortion.label(param)} for this note" else "Distortion: V00-V08"
            FX_TWO_TONE -> when (param) {
                0 -> "Two-tone off"
                1 -> "Two-tone: POKEY 1 and 2 resync each other"
                2 -> "Two-tone sync: POKEY 2 restarts POKEY 1"
                else -> "Two-tone: W00-W02"
            }
            else -> "Unknown effect"
        }
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = kotlin.math.round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }
}
