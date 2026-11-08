package org.thortracker.system.opl3

import org.thortracker.backend.Opl3PlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec

/**
 * OPL3 cell semantics: Thor Tracker's own player (docs/AUDIO_ENGINE.md, "OPL3: Effects"), up to
 * [EFFECT_COLUMNS] effect columns per channel, FamiTracker letters where meanings match. FM locks (`E`, `I`, `M`, `W`)
 * and chords (`H`, `K`) apply to the note keyed on their row; volume and pan change sounding notes.
 */
object Opl3Rules {
    /** Absolute notes the player takes: C-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES = 0 until Opl3PlayerImage.NOTES

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = Opl3PlayerImage.MAX_EFFECTS

    /** Channels 1-6 (0-based 0..5) own a 4-op pair; 7-9 are 2-op only. */
    const val FOUR_OP_CHANNELS = Opl3PlayerImage.FOUR_OP_CHANNELS

    val CHANNEL_NAMES = (1..Opl3PlayerImage.CHANNELS).map { "FM $it" }

    // Effect codes: the player's (generated from opl3_image.h).
    const val FX_ARPEGGIO = Opl3PlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = Opl3PlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = Opl3PlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = Opl3PlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = Opl3PlayerImage.FX_VIBRATO
    const val FX_PAN = Opl3PlayerImage.FX_PAN
    const val FX_VOLUME_SLIDE = Opl3PlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = Opl3PlayerImage.FX_JUMP
    const val FX_VOLUME = Opl3PlayerImage.FX_VOLUME
    const val FX_BREAK = Opl3PlayerImage.FX_BREAK
    const val FX_FEEDBACK = Opl3PlayerImage.FX_FEEDBACK
    const val FX_SPEED = Opl3PlayerImage.FX_SPEED
    const val FX_DELAY = Opl3PlayerImage.FX_DELAY
    const val FX_CHORD = Opl3PlayerImage.FX_CHORD
    const val FX_BRIGHTNESS = Opl3PlayerImage.FX_BRIGHTNESS
    const val FX_SHAPE = Opl3PlayerImage.FX_SHAPE
    const val FX_MULT = Opl3PlayerImage.FX_MULT
    const val FX_FINE = Opl3PlayerImage.FX_FINE
    const val FX_CUT = Opl3PlayerImage.FX_CUT
    const val FX_WAVE = Opl3PlayerImage.FX_WAVE

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** Effects that act only on the note keyed on their row (parameter locks and chords). */
    val NOTE_EFFECTS = setOf(FX_FEEDBACK, FX_BRIGHTNESS, FX_MULT, FX_WAVE, FX_CHORD, FX_SHAPE)

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note); the next note without 0xy plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding channel glides there without a new attack (no locks or chord); 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "Wobble the pitch (depth F: ±1.9 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x≠0 left, y≠0 right",
            "Output left (10), right (01) or both (11); 800 silences. Applies at once, chord voices included.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the channel volume every tick until A00 (A01: full to silent in 8.4 s; A0F in 0.56 s).", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-3F",
            "Channel volume from this row on (start 3F), 0.75 dB per step on the carriers; sounding notes follow.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_FEEDBACK, "Feedback lock", "xx: 0-7",
            "Operator 1 feedback for the note on this row only.", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_CHORD, "Chord", "xy: +x, +y semitones",
            "Also sound +x and +y on spare chip voices with the note on this row (2-op instruments); released with it.", true),
        EffectSpec(FX_BRIGHTNESS, "Brightness lock", "xx: 00-3F",
            "Every modulator's level = 3F − xx for the note on this row (3F brightest); additive sounds have none.", true),
        EffectSpec(FX_SHAPE, "Chord shape", "xx: shape 01-",
            "Sound chord shape xx (up to 4 intervals, inversions) with the note on this row, on spare voices (2-op instruments).", true),
        EffectSpec(FX_MULT, "Ratio lock", "xy: operator x 1-4, multiplier y 0-F",
            "Operator x's frequency multiplier (0 = ×½) for the note on this row.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Key off at tick xx of this row (the release plays), like an OFF at that tick.", true),
        EffectSpec(FX_WAVE, "Waveform lock", "xy: operator x 1-4 (0 = all), wave y 0-7",
            "Operator waveform for the note on this row.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load (the checks `o3_load_image` makes), given the song's order and shape counts; or null. */
    fun paramProblem(code: Int, param: Int, orders: Int, shapes: Int): String? {
        val x = param shr 4
        val y = param and 0x0F
        val fx = effectText(code, param)
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > 0x3F) "$fx: volumes are C00-C3F" else null
            FX_FEEDBACK -> if (param > 7) "$fx: feedback is E00-E07" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_BRIGHTNESS -> if (param > 0x3F) "$fx: brightness is I00-I3F" else null
            FX_SHAPE -> when {
                param == 0 -> "K00: chord shapes are K01-K${NumberText.hex(maxOf(shapes, 1), 2)}"
                param > shapes -> "$fx names chord shape $param; the song has $shapes"
                else -> null
            }
            FX_MULT -> if (x !in 1..4) "$fx: the operator digit is 1-4" else null
            FX_WAVE -> if (x > 4 || y > 7) "$fx: operator 0-4 (0 = all), waveform 0-7" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) return "note ${cell.note} is outside C-0..B-7 (0-95)"
        if (cell.instrument !in 0..Opl3System.SLOTS) return "instrument ${cell.instrument} is outside 0-${Opl3System.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the OPL3 plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the OPL3"
        }
        return null
    }

    /** Human-readable meaning of [effect]/[param] for the inspector and picker. */
    fun describe(song: Song?, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up ${param}/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down ${param}/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento ${param}/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_PAN -> when {
                x != 0 && y != 0 -> "Pan centre (both outputs)"
                x != 0 -> "Pan left"
                y != 0 -> "Pan right"
                else -> "Pan: silent (no output)"
            }
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> "Volume $hex of 3F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_FEEDBACK -> "Feedback $param for this note"
            FX_SPEED -> "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_CHORD -> if (param == 0) "Chord: none" else "Chord: note, +$x, +$y semitones"
            FX_BRIGHTNESS -> "Brightness $hex of 3F for this note"
            FX_SHAPE -> song?.opl3?.chords?.getOrNull(param - 1)?.let { "Chord shape $hex ${it.name}: ${it.intervals.joinToString(" ") { i -> signed(i) }}" }
                ?: "Chord shape $hex (not in the song's table)"
            FX_MULT -> "Operator $x ratio ${Opl3Curated.multText(y)} for this note"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_WAVE -> "${if (x == 0) "All operators" else "Operator $x"} waveform ${Opl3Curated.WAVE_NAMES.getOrElse(y) { "?" }} for this note"
            else -> "Unknown effect"
        }
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = kotlin.math.round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }
}
