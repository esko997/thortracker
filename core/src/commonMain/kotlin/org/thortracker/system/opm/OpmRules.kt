package org.thortracker.system.opm

import org.thortracker.backend.OpmPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec

/**
 * OPM cell semantics: Thor Tracker's own player on a YM2151 (docs/AUDIO_ENGINE.md, "OPM: Effects"), up to
 * [EFFECT_COLUMNS] effect columns per channel, FamiTracker letters where meanings match. FM locks (`E`, `I`, `M`) apply
 * to the note keyed on their row; volume, pan and `Hxy` change sounding notes; the shared effects (`L`, `T`, `V`, `W`,
 * `X`, `Q`, and `N` on FM 8) set the chip's one LFO, CSM timer and noise generator for every channel.
 */
object OpmRules {
    /** Absolute notes the 4 MHz chip sounds: D#0..D-8 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES: IntRange = OpmPlayerImage.NOTE_MIN..OpmPlayerImage.NOTE_MAX

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = OpmPlayerImage.MAX_EFFECTS

    /** FM 8 (0-based 7): the only channel whose operator 4 can play the noise generator. */
    const val NOISE_CHANNEL = OpmPlayerImage.NOISE_CHANNEL

    val CHANNEL_NAMES = (1..OpmPlayerImage.CHANNELS).map { "FM $it" }

    // Effect codes: the player's (generated from opm_image.h).
    const val FX_ARPEGGIO = OpmPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = OpmPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = OpmPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = OpmPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = OpmPlayerImage.FX_VIBRATO
    const val FX_PAN = OpmPlayerImage.FX_PAN
    const val FX_VOLUME_SLIDE = OpmPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = OpmPlayerImage.FX_JUMP
    const val FX_VOLUME = OpmPlayerImage.FX_VOLUME
    const val FX_BREAK = OpmPlayerImage.FX_BREAK
    const val FX_FEEDBACK = OpmPlayerImage.FX_FEEDBACK
    const val FX_SPEED = OpmPlayerImage.FX_SPEED
    const val FX_DELAY = OpmPlayerImage.FX_DELAY
    const val FX_SENSITIVITY = OpmPlayerImage.FX_SENSITIVITY
    const val FX_BRIGHTNESS = OpmPlayerImage.FX_BRIGHTNESS
    const val FX_LFO_RATE = OpmPlayerImage.FX_LFO_RATE
    const val FX_MULT = OpmPlayerImage.FX_MULT
    const val FX_NOISE = OpmPlayerImage.FX_NOISE
    const val FX_FINE = OpmPlayerImage.FX_FINE
    const val FX_TIMER_A = OpmPlayerImage.FX_TIMER_A
    const val FX_CUT = OpmPlayerImage.FX_CUT
    const val FX_AMD = OpmPlayerImage.FX_AMD
    const val FX_PMD = OpmPlayerImage.FX_PMD
    const val FX_LFO_WAVE = OpmPlayerImage.FX_LFO_WAVE
    const val FX_CSM = OpmPlayerImage.FX_CSM

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** Effects that act only on the note keyed on their row (parameter locks). */
    val NOTE_EFFECTS = setOf(FX_FEEDBACK, FX_BRIGHTNESS, FX_MULT)

    /** Effects that set the chip's shared state (every channel hears them) at once. */
    val SHARED_EFFECTS = setOf(FX_LFO_RATE, FX_AMD, FX_PMD, FX_LFO_WAVE, FX_CSM, FX_TIMER_A)

    /** The LFO waveforms of register $1B bits 0-1. */
    val LFO_WAVE_NAMES = listOf("Saw", "Square", "Triangle", "Noise")

    /** The chip's sample rate at 4 MHz: 62500 Hz (the LFO, timer A and noise count in it). */
    private val NATIVE_HZ = OpmPlayerImage.CLOCK.toDouble() / OpmPlayerImage.NATIVE_DIVIDER

    /** LFO frequency of LFRQ [rate] (0..255) at 4 MHz: 62500 · ((16 + rate mod 16) << (rate / 16)) / 2^30 Hz. */
    fun lfoHz(rate: Int): Double {
        val r = rate.coerceIn(0, 255)
        return NATIVE_HZ * ((16 + (r and 15)) shl (r shr 4)) / (1L shl 30).toDouble()
    }

    /** CSM retrigger rate of timer A value [timerA] (0..1023): 62500 / (1024 − NA) Hz. */
    fun csmHz(timerA: Int): Double = NATIVE_HZ / (1024 - timerA.coerceIn(0, 1023))

    /** Noise generator frequency of NFRQ [nfrq] (0..31): 4000000 / (32 · (32 − NFRQ)) Hz. */
    fun noiseHz(nfrq: Int): Double = OpmPlayerImage.CLOCK.toDouble() / (32 * (32 - nfrq.coerceIn(0, 31)))

    /** A frequency as people read it: "0.0009 Hz", "5.72 Hz", "59.1 Hz", "488 Hz", "7.8 kHz". */
    fun hzText(hz: Double): String = when {
        hz >= 1000 -> NumberText.fixed(hz / 1000, 1) + " kHz"
        hz >= 100 -> NumberText.fixed(hz, 0) + " Hz"
        hz >= 10 -> NumberText.fixed(hz, 1) + " Hz"
        hz >= 1 -> NumberText.fixed(hz, 2) + " Hz"
        hz >= 0.01 -> NumberText.fixed(hz, 3) + " Hz"
        else -> NumberText.fixed(hz, 4) + " Hz"
    }

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note); the next note without 0xy plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding channel glides there without a new attack (no locks); 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "The player's own pitch wobble (depth F: ±1.86 semitones), separate from the chip's LFO; 4x0 stops it; " +
                "the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x≠0 left, y≠0 right",
            "Output left (10), right (01) or both (11); 800 silences. Applies at once to the sounding note.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the channel volume every tick until A00 (A01: full to silent in 16.9 s; A0F in 1.1 s).", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-7F",
            "Channel volume from this row on (start 7F), 0.75 dB per step on the carriers; sounding notes follow.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_FEEDBACK, "Feedback lock", "xx: 0-7",
            "Operator 1 feedback for the note on this row only.", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_SENSITIVITY, "LFO sensitivity", "xy: x PMS 0-7, y AMS 0-3",
            "How much of the shared LFO this channel takes (x pitch, y amplitude), at once on the sounding note and for " +
                "a note on this row; the next note without Hxy takes its instrument's.", true),
        EffectSpec(FX_BRIGHTNESS, "Brightness lock", "xx: 00-7F",
            "Every modulator's level = 7F − xx for the note on this row (7F brightest); algorithm 7 has none.", true),
        EffectSpec(FX_LFO_RATE, "LFO rate", "xx: LFRQ 00-FF",
            "The chip's one LFO frequency, for every channel, from this row on (00: 0.0009 Hz, C8: 5.72 Hz, FF: 59.1 Hz).", true),
        EffectSpec(FX_MULT, "Ratio lock", "xy: operator x 1-4, multiplier y 0-F",
            "Operator x's frequency multiplier (0 = ×½) for the note on this row; DT1 and DT2 kept.", true),
        EffectSpec(FX_NOISE, "Noise frequency", "xx: NFRQ 00-1F (FM 8)",
            "The noise generator's frequency at once (3.9 kHz at 00 up to 125 kHz at 1F) and for a note on this row; " +
                "noise on or off stays as FM 8's instrument set it. FM 8 only.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_TIMER_A, "Timer A", "xx: CSM rate, high byte",
            "Timer A's high 8 bits for every channel (NA = xx · 4 + its low bits): with CSM on, every operator is keyed " +
                "62500 / (1024 − NA) times a second, from the next overflow.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Key off at tick xx of this row (the release plays), like an OFF at that tick.", true),
        EffectSpec(FX_AMD, "LFO amplitude depth", "xx: AMD 00-7F",
            "The shared LFO's tremolo depth from this row on; each channel takes it by its AMS and its operators' AM.", true),
        EffectSpec(FX_PMD, "LFO pitch depth", "xx: PMD 00-7F",
            "The shared LFO's vibrato depth from this row on; each channel takes it by its PMS.", true),
        EffectSpec(FX_LFO_WAVE, "LFO waveform", "xx: 00 saw, 01 square, 02 triangle, 03 noise",
            "The shared LFO's waveform from this row on, for every channel.", true),
        EffectSpec(FX_CSM, "CSM", "xx: 00 off, 01 on",
            "CSM mode for the whole chip: at each timer A overflow every channel's four operators are keyed on for one " +
                "sample (released notes restart their attack). X00 ends it.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    /** What channels other than FM 8 play: everything except `Nxx`. */
    private val TONE_EFFECTS = EFFECTS.filter { it.code != FX_NOISE }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** Effects [channel] plays (the loader rejects `Nxx` off FM 8). */
    fun effectsFor(channel: Int): List<EffectSpec> = if (channel == NOISE_CHANNEL) EFFECTS else TONE_EFFECTS

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load on [channel] (the checks `om_load_image` makes), given the order count; or null. */
    fun paramProblem(code: Int, param: Int, orders: Int, channel: Int): String? {
        val x = param shr 4
        val y = param and 0x0F
        val fx = effectText(code, param)
        if (effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the OPM"
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > 0x7F) "$fx: volumes are C00-C7F" else null
            FX_FEEDBACK -> if (param > 7) "$fx: feedback is E00-E07" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_SENSITIVITY -> if (x > 7 || y > 3) "$fx: PMS (x) is 0-7 and AMS (y) 0-3" else null
            FX_BRIGHTNESS -> if (param > 0x7F) "$fx: brightness is I00-I7F" else null
            FX_MULT -> if (x !in 1..4) "$fx: the operator digit is 1-4" else null
            FX_NOISE -> when {
                channel != NOISE_CHANNEL -> "$fx sets the noise generator; only FM 8 plays noise, so Nxx belongs on FM 8"
                param > 0x1F -> "$fx: noise frequencies are N00-N1F"
                else -> null
            }
            FX_AMD -> if (param > 0x7F) "$fx: amplitude depths are T00-T7F" else null
            FX_PMD -> if (param > 0x7F) "$fx: pitch depths are V00-V7F" else null
            FX_LFO_WAVE -> if (param > 3) "$fx: LFO waveforms are W00 (saw), W01 (square), W02 (triangle) and W03 (noise)" else null
            FX_CSM -> if (param > 1) "$fx: CSM is X00 (off) or X01 (on)" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and channel, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) {
            return "note ${cell.note} is outside D#0..D-8 (${NOTES.first}-${NOTES.last}), the notes the 4 MHz YM2151 sounds"
        }
        if (cell.instrument !in 0..OpmSystem.SLOTS) return "instrument ${cell.instrument} is outside 0-${OpmSystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the OPM player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the OPM"
        }
        return null
    }

    /** Human-readable meaning of [effect]/[param] on [channel] for the inspector and picker. */
    fun describe(song: Song?, channel: Int, effect: Int, param: Int): String {
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
            FX_VOLUME -> "Volume $hex of 7F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_FEEDBACK -> "Feedback $param for this note"
            FX_SPEED -> "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_SENSITIVITY -> if (x > 7 || y > 3) "LFO sensitivity: invalid (PMS 0-7, AMS 0-3)"
                else "LFO sensitivity: PMS $x (${OpmCurated.PMS_TEXT[x]} at PMD 7F), AMS $y (${OpmCurated.AMS_TEXT[y]} at AMD 7F)"
            FX_BRIGHTNESS -> "Brightness $hex of 7F for this note"
            FX_LFO_RATE -> "LFO rate $hex: ${hzText(lfoHz(param))}, every channel"
            FX_MULT -> "Operator $x ratio ${OpmCurated.multText(y)} for this note"
            FX_NOISE -> when {
                channel != NOISE_CHANNEL -> "Noise frequency: FM 8 only"
                param > 0x1F -> "Noise frequency: invalid (00-1F)"
                else -> "Noise frequency $hex: ${hzText(noiseHz(param))}"
            }
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_TIMER_A -> {
                val low = song?.let { (it.sound as? OpmSound)?.timerA?.and(3) } ?: 0
                val na = (param shl 2) or low
                "Timer A $na: CSM rate ${hzText(csmHz(na))}"
            }
            FX_CUT -> "Note off at tick $param"
            FX_AMD -> if (param > 0x7F) "LFO amplitude depth: invalid (00-7F)" else "LFO amplitude depth (AMD) $param of 127, every channel"
            FX_PMD -> if (param > 0x7F) "LFO pitch depth: invalid (00-7F)" else "LFO pitch depth (PMD) $param of 127, every channel"
            FX_LFO_WAVE -> LFO_WAVE_NAMES.getOrNull(param)?.let { "LFO waveform: ${it.lowercase()}, every channel" } ?: "LFO waveform: invalid (00-03)"
            FX_CSM -> when (param) {
                0 -> "CSM off"
                1 -> "CSM on: every operator keyed at each timer A overflow"
                else -> "CSM: invalid (00 or 01)"
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
