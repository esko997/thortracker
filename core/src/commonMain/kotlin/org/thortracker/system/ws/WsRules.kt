package org.thortracker.system.ws

import org.thortracker.backend.WsPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * WonderSwan cell semantics: Thor Tracker's own player on the WonderSwan sound unit (docs/AUDIO_ENGINE.md,
 * "WonderSwan: Effects"), up to [EFFECT_COLUMNS] effect columns per lane, FamiTracker letters where meanings match.
 * Lanes are CH1-CH4 (0..3) and, on the WonderSwan Color, Hyper Voice (4). `K` and `T` set the console's shared speaker
 * shift and voice timer for every lane; `N`, `U`/`V`, `W` and `H` exist only on the lanes whose hardware they drive.
 */
object WsRules {
    /** Absolute notes the player takes: G-1..B-7 (0 = C-0, 57 = A-4; F#-1 would need a period of 2076 > 2048). */
    val NOTES: IntRange = WsPlayerImage.NOTE_MIN..WsPlayerImage.NOTE_MAX

    /** Sound slots in the one bank. */
    const val SLOTS = WsPlayerImage.MAX_INSTRUMENTS

    /** Effect columns per lane (the player's limit). */
    const val EFFECT_COLUMNS = WsPlayerImage.MAX_EFFECTS

    /** CH2 (0-based 1): the channel with the voice (8-bit sample) output. */
    const val VOICE_LANE = WsPlayerImage.VOICE_CHANNEL

    /** CH3 (0-based 2): the channel with the hardware sweep. */
    const val SWEEP_LANE = WsPlayerImage.SWEEP_CHANNEL

    /** CH4 (0-based 3): the channel with the noise generator. */
    const val NOISE_LANE = WsPlayerImage.NOISE_CHANNEL

    /** Hyper Voice (0-based 4, WonderSwan Color only). */
    const val HYPER_VOICE = WsPlayerImage.HYPER_VOICE

    /** The lanes of the original model (CH1-CH4) and of the Color (plus Hyper Voice). */
    const val LANES_MONO = WsPlayerImage.CHANNELS_MONO
    const val LANES_COLOR = WsPlayerImage.CHANNELS

    fun laneCount(color: Boolean): Int = if (color) LANES_COLOR else LANES_MONO

    fun laneNames(color: Boolean): List<String> = List(laneCount(color)) { if (it == HYPER_VOICE) "Hyper Voice" else "CH${it + 1}" }

    // Effect codes: the player's (generated from ws_image.h).
    const val FX_ARPEGGIO = WsPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = WsPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = WsPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = WsPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = WsPlayerImage.FX_VIBRATO
    const val FX_PAN = WsPlayerImage.FX_PAN
    const val FX_VOLUME_SLIDE = WsPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = WsPlayerImage.FX_JUMP
    const val FX_VOLUME = WsPlayerImage.FX_VOLUME
    const val FX_BREAK = WsPlayerImage.FX_BREAK
    const val FX_SPEED = WsPlayerImage.FX_SPEED
    const val FX_DELAY = WsPlayerImage.FX_DELAY
    const val FX_HOLD = WsPlayerImage.FX_HOLD
    const val FX_SHIFT = WsPlayerImage.FX_SHIFT
    const val FX_TAP = WsPlayerImage.FX_TAP
    const val FX_FINE = WsPlayerImage.FX_FINE
    const val FX_CUT = WsPlayerImage.FX_CUT
    const val FX_TIMER = WsPlayerImage.FX_TIMER
    const val FX_SWEEP = WsPlayerImage.FX_SWEEP
    const val FX_SWEEP_TIME = WsPlayerImage.FX_SWEEP_TIME
    const val FX_WAVE = WsPlayerImage.FX_WAVE

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** Largest lane volume (`Cxx`). */
    const val MAX_VOLUME = 0x0F

    /** Effects that set the console's shared state (every lane hears them). */
    val SHARED_EFFECTS = setOf(FX_SHIFT, FX_TIMER)

    // ---- clocks and pitch ---------------------------------------------------------------------------------------

    /** The sound unit's clock: 12.288 MHz / 4. */
    const val CPU_HZ = 3_072_000

    /** One display frame = one player tick: 159 lines of 256 cycles. */
    const val TICK_CYCLES = 40704

    /** Player ticks per second: 3072000 / 40704 = 75.47 ([WsPlayerImage.TICKS_PER_SECOND]). */
    const val TICK_HZ = 3_072_000.0 / 40_704

    /** A 32-step wave sounds at 96000 / N Hz for the period N = 2048 − d (the 11-bit frequency register d). */
    const val WAVE_HZ = CPU_HZ / 32

    /** Sound DMA's rates in bytes per second, by rate code 0..3. */
    val DMA_RATES = listOf(4000, 6000, 12000, 24000)

    /**
     * `WSP_PERIOD_Q16[i]`: round(96000 / (440 · 2^((i / 64 − 57) / 12)) · 65536) in double precision (the period N of
     * pitch i of octave 0 in 16.16).
     */
    fun periodQ16(i: Int): Long = (WAVE_HZ / (440.0 * 2.0.pow((i / 64.0 - 57) / 12)) * 65536).roundToLong()

    /**
     * The period N (1..2048) the player plays for [pitch] (1/64 semitone, 0 = C-0; clamped to the player's range):
     * N = (T[i] + (1 << (15 + o))) >> (16 + o) for octave o = p / 768 and i = p mod 768. The register is 2048 − N.
     */
    fun period(pitch: Int): Int {
        val p = pitch.coerceIn(WsPlayerImage.PITCH_MIN, WsPlayerImage.PITCH_MAX)
        val o = p / 768
        return ((periodQ16(p % 768) + (1L shl (15 + o))) shr (16 + o)).toInt()
    }

    /**
     * `wsp_sample_step`: frames per voice-timer interrupt in 16.16 for a CPU-fed sample with root note [root] playing
     * [pitch] (1/64 semitone): T[rp mod 768] << (16 + δ) / T[p mod 768] for δ = p / 768 − rp / 768 ≥ 0, else
     * ((T[rp mod 768] << 16) / T[p mod 768]) >> −δ, truncating. The root plays 65536, one frame per interrupt.
     */
    fun sampleStep(pitch: Int, root: Int): Long {
        val p = pitch.coerceIn(WsPlayerImage.PITCH_MIN, WsPlayerImage.PITCH_MAX)
        val rp = root * 64
        val d = p / 768 - rp / 768
        val tr = periodQ16(rp % 768)
        val tp = periodQ16(p % 768)
        return if (d >= 0) (tr shl (16 + d)) / tp else ((tr shl 16) / tp) shr -d
    }

    /** The frequency a tone [note] sounds at: 96000 / N. */
    fun toneHz(note: Int): Double = WAVE_HZ.toDouble() / period(note * 64)

    /** The rate channel 4's LFSR steps at for [note]: once per period, 3072000 / N (32 × the tone frequency). */
    fun noiseClockHz(note: Int): Double = CPU_HZ.toDouble() / period(note * 64)

    /** The sequence length of each LFSR tap mode (the WSdev wiki's table): 32767 is noise, short ones a buzzing pitch. */
    val TAP_LENGTHS = listOf(32767, 1953, 254, 217, 73, 63, 42, 28)

    /** The voice timer's interrupt rate for reload [timer] (1..255): 12000 / R Hz. */
    fun timerHz(timer: Int): Double = 12000.0 / timer.coerceIn(1, WsPlayerImage.MAX_TIMER)

    /** The voice timer's rate in words: "6000 Hz", "47.1 Hz". */
    fun timerText(timer: Int): String = hzText(timerHz(timer))

    /** A frequency as people read it: "0.42 Hz", "5.72 Hz", "59.1 Hz", "440 Hz", "6.0 kHz". */
    fun hzText(hz: Double): String = when {
        hz >= 1000 -> NumberText.fixed(hz / 1000, 1) + " kHz"
        hz >= 100 -> NumberText.fixed(hz, 0) + " Hz"
        hz >= 10 -> NumberText.fixed(hz, 1) + " Hz"
        hz >= 1 -> NumberText.fixed(hz, 2) + " Hz"
        else -> NumberText.fixed(hz, 3) + " Hz"
    }

    /** Speaker shift [shift] in words: the share of the 8-bit sum the speaker gets. */
    fun shiftText(shift: Int): String = listOf("100 %", "50 %", "25 %", "12.5 %").getOrElse(shift) { "?" }

    /** `$94` voice levels: per side 0 off, 1..11 half (50 %), 12..15 full. */
    fun voiceLevel(v: Int): String = when {
        v >= 12 -> "100 %"
        v >= 1 -> "50 %"
        else -> "off"
    }

    /** Hyper Voice's level for lane volume [v]: 0 off, 3..: shift 3, 6..: 2, 12..: 0 (100 %); else 12.5 %. */
    fun hyperVoiceLevel(v: Int): String = when {
        v == 0 -> "off"
        v >= 12 -> "100 %"
        v >= 6 -> "50 %"
        v >= 3 -> "25 %"
        else -> "12.5 %"
    }

    // ---- effects ------------------------------------------------------------------------------------------------

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note), on noise and CPU-fed samples too; " +
                "the next note without 0xy plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300 (a sweeping CH3 note restarts its sweep when its " +
                "period changes).", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding tone or CPU-fed sample glides there without restarting; 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "The player's pitch wobble (depth F: ±1.86 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x left, y right, 0-F",
            "Per-side scale from this row (FF at the start). Tones and noise: 16 steps per side; CH2's voice: off, 50 % or " +
                "100 % per side; Hyper Voice: each side on or off.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the lane volume every tick until A00.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order", "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Lane volume from this row on (start 0F), multiplying the volume envelope; sounding notes follow.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all lanes (75.5 ticks per second); the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this lane's row (note, sound and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_HOLD, "DMA hold", "xx: 00 run, 01 hold",
            "WonderSwan Color, CH2 and Hyper Voice: pause the lane's Sound DMA sample (the DMA sends zeros) from this row " +
                "until H00 or the next note row.", true),
        EffectSpec(FX_SHIFT, "Speaker shift", "xx: 00-03",
            "For every lane: the speaker's share of the sum, 100, 50, 25 or 12.5 % before its 8-bit wrap (Speaker output " +
                "only; headphones ignore it).", true),
        EffectSpec(FX_TAP, "Noise tap", "xx: 00-07",
            "CH4 noise: the LFSR's tap mode from this row until the next note row without N; ignored by sounds with a tap " +
                "sequence.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick", "OFF at tick xx of this row, like an OFF note at that tick.", true),
        EffectSpec(FX_TIMER, "Voice timer", "xx: reload 01-FF",
            "For every lane: CPU-fed samples interrupt at 12000 / xx Hz, from the timer's next reload. Sample notes play " +
                "relative to it.", true),
        EffectSpec(FX_SWEEP, "Sweep value", "xx: signed, 00 off",
            "CH3: added to the 11-bit frequency register every sweep step (wrapping) from this row until the next note row " +
                "without U; U00 stops the sound's sweep.", true),
        EffectSpec(FX_SWEEP_TIME, "Sweep time", "xx: 00-1F",
            "CH3: a sweep step every (xx + 1) / 375 s from this row until the next note row without V.", true),
        EffectSpec(FX_WAVE, "Wave", "xx: wave 00..",
            "CH1-CH4 tones: the sounding tone's wave (loaded at once) and later notes' until a note row without W; ignored " +
                "by sounds with a wave sequence.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** The effects that exist on the [color] model (`H` only on the Color). */
    fun effects(color: Boolean): List<EffectSpec> = if (color) EFFECTS else EFFECTS.filter { it.code != FX_HOLD }

    /** True when effect [code] may stand on [lane] of the [color] model (the loader's lane rules). */
    fun allowed(code: Int, lane: Int, color: Boolean): Boolean = when (code) {
        FX_TAP -> lane == NOISE_LANE
        FX_SWEEP, FX_SWEEP_TIME -> lane == SWEEP_LANE
        FX_WAVE -> lane in 0 until LANES_MONO
        FX_HOLD -> color && (lane == VOICE_LANE || lane == HYPER_VOICE)
        else -> effect(code) != null
    }

    /** The effects usable on [lane] of the [color] model. */
    fun effectsFor(lane: Int, color: Boolean): List<EffectSpec> = EFFECTS.filter { allowed(it.code, lane, color) }

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /**
     * Why effect [code]/[param] on [lane] cannot load (the checks `ws_load_image` makes), given the model, the order
     * count and the song's wave count; or null.
     */
    fun paramProblem(code: Int, param: Int, lane: Int, color: Boolean, orders: Int, waves: Int): String? {
        val fx = effectText(code, param)
        if (effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the WonderSwan"
        if (!allowed(code, lane, color)) return when (code) {
            FX_TAP -> "$fx: the noise tap is channel 4's; N belongs on CH4"
            FX_SWEEP, FX_SWEEP_TIME -> "$fx: the hardware sweep is channel 3's; ${EffectSpec.charOf(code)} belongs on CH3"
            FX_WAVE -> "$fx: Hyper Voice plays samples, not waves; W belongs on CH1-CH4"
            else -> if (!color) "$fx: Sound DMA (and its hold) exists only on the WonderSwan Color"
                else "$fx: DMA hold acts on CH2 and Hyper Voice only"
        }
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > MAX_VOLUME) "$fx: volumes are C00-C0F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_TIMER -> if (param == 0) "T00: the voice timer reloads are T01-TFF" else null
            FX_HOLD -> if (param > 1) "$fx: H00 runs, H01 holds" else null
            FX_SHIFT -> if (param > WsPlayerImage.MAX_SHIFT) "$fx: speaker shifts are K00-K03" else null
            FX_TAP -> if (param > WsPlayerImage.MAX_TAP) "$fx: tap modes are N00-N07" else null
            FX_SWEEP_TIME -> if (param > WsPlayerImage.MAX_SWEEP_TIME) "$fx: sweep times are V00-V1F" else null
            FX_WAVE -> when {
                waves == 0 -> "$fx: the song has no waves; add one in the Wave view"
                param >= waves -> "$fx: the song's waves are W00-W${NumberText.hex(waves - 1, 2)}"
                else -> null
            }
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and lane, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) {
            return "note ${cell.note} is outside G-1..B-7 (${NOTES.first}-${NOTES.last}), the notes the WonderSwan's periods reach"
        }
        if (cell.instrument !in 0..SLOTS) return "sound ${cell.instrument} is outside 0-$SLOTS"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the WonderSwan player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the WonderSwan"
        }
        return null
    }

    /** Human-readable meaning of [effect]/[param] on [lane] for the inspector and picker; [song] names waves. */
    fun describe(song: Song?, lane: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_PAN -> when (lane) {
                VOICE_LANE -> "Pan: left $x, right $y of 15 (voice samples: left ${voiceLevel(x)}, right ${voiceLevel(y)})"
                HYPER_VOICE -> "Pan: left ${if (x > 0) "on" else "off"}, right ${if (y > 0) "on" else "off"}"
                else -> if (param == 0) "Pan: silent" else "Pan: left $x, right $y of 15"
            }
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> if (param > MAX_VOLUME) "Volume: invalid (00-0F)" else "Volume $hex of 0F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_SPEED -> if (param == 0) "Speed: invalid (01-FF)" else "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_HOLD -> when (param) {
                0 -> "DMA hold off: the lane's DMA sample runs"
                1 -> "DMA hold: the lane's DMA sample pauses"
                else -> "DMA hold: invalid (00-01)"
            }
            FX_SHIFT -> if (param > WsPlayerImage.MAX_SHIFT) "Speaker shift: invalid (00-03)" else "Speaker shift $param (${shiftText(param)}), every lane"
            FX_TAP -> if (param > WsPlayerImage.MAX_TAP) "Noise tap: invalid (00-07)" else "Noise tap mode $param (${TAP_LENGTHS[param]}-step sequence)"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_TIMER -> if (param == 0) "Voice timer: invalid (01-FF)" else "Voice timer ${timerText(param)}, every lane"
            FX_SWEEP -> if (param == 0) "Sweep off" else "Sweep ${signed(param.toByte().toInt())} per step"
            FX_SWEEP_TIME -> if (param > WsPlayerImage.MAX_SWEEP_TIME) "Sweep time: invalid (00-1F)"
                else "Sweep step every ${param + 1}/375 s (${NumberText.fixed((param + 1) * 1000.0 / 375, 1)} ms)"
            FX_WAVE -> {
                val waves = (song?.sound as? WsSound)?.waves?.size
                if (waves != null && param >= waves) "Wave $hex: the song has no such wave" else "Wave $hex for the sounding tone and later notes"
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
