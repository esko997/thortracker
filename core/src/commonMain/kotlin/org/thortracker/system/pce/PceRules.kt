package org.thortracker.system.pce

import org.thortracker.backend.PcePlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * PC Engine cell semantics: Thor Tracker's own player on a HuC6280 PSG (docs/AUDIO_ENGINE.md, "PC Engine: Effects"),
 * up to [EFFECT_COLUMNS] effect columns per channel, FamiTracker letters where meanings match. Volume, pan and `Wxx`
 * change sounding notes; the shared effects (`L`, `M`, `T`, `X`) set the chip's one LFO, DDA timer and master balance
 * for every channel.
 */
object PceRules {
    /** Absolute notes the player takes: A-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES: IntRange = PcePlayerImage.NOTE_MIN..PcePlayerImage.NOTE_MAX

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = PcePlayerImage.MAX_EFFECTS

    /** PSG 5 and PSG 6 (0-based 4 and 5): the channels with a noise generator. */
    val NOISE_CHANNELS: IntRange = PcePlayerImage.NOISE_FIRST until PcePlayerImage.CHANNELS

    /** PSG 1 (0-based 0): the channel the LFO modulates. */
    const val LFO_TARGET = 0

    /** PSG 2 (0-based 1): the channel whose wave is the LFO. */
    const val LFO_SOURCE = 1

    val CHANNEL_NAMES = (1..PcePlayerImage.CHANNELS).map { "PSG $it" }

    // Effect codes: the player's (generated from pce_image.h).
    const val FX_ARPEGGIO = PcePlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = PcePlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = PcePlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = PcePlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = PcePlayerImage.FX_VIBRATO
    const val FX_PAN = PcePlayerImage.FX_PAN
    const val FX_VOLUME_SLIDE = PcePlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = PcePlayerImage.FX_JUMP
    const val FX_VOLUME = PcePlayerImage.FX_VOLUME
    const val FX_BREAK = PcePlayerImage.FX_BREAK
    const val FX_SPEED = PcePlayerImage.FX_SPEED
    const val FX_DELAY = PcePlayerImage.FX_DELAY
    const val FX_LFO_FREQ = PcePlayerImage.FX_LFO_FREQ
    const val FX_LFO_CTRL = PcePlayerImage.FX_LFO_CTRL
    const val FX_FINE = PcePlayerImage.FX_FINE
    const val FX_CUT = PcePlayerImage.FX_CUT
    const val FX_TIMER = PcePlayerImage.FX_TIMER
    const val FX_WAVE = PcePlayerImage.FX_WAVE
    const val FX_MASTER = PcePlayerImage.FX_MASTER

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** Effects that set the chip's shared state (every channel hears them) at once. */
    val SHARED_EFFECTS = setOf(FX_LFO_FREQ, FX_LFO_CTRL, FX_TIMER, FX_MASTER)

    /** Largest channel volume (`Cxx`). */
    const val MAX_VOLUME = 0x1F

    /** Register 9 bit 7: halt the LFO and reset PSG 2's wave position. */
    const val LFO_HALT = 0x80

    /** True when [value] is a register 9 value the player takes: 00-03 or 80-83. */
    fun lfoControlValid(value: Int): Boolean = (value and LFO_HALT.inv()) in 0..3 && value in 0..0xFF

    /** True when register 9 [value] makes PSG 2's wave bend PSG 1's pitch: a depth set and not halted. */
    fun lfoBends(value: Int): Boolean = value and 3 != 0 && value and LFO_HALT == 0

    /** The CPU clock the timer, the PSG's timestamps and the noise count: 78750000 / 11 Hz. */
    const val CPU_HZ = 78_750_000.0 / 11

    /** The waveform counters' clock: CPU / 2 = 3579545.45 Hz. */
    const val PSG_HZ = CPU_HZ / 2

    /**
     * The 12-bit period the player writes for [pitch] (1/64 semitone, 0 = C-0; clamped to the player's range): the
     * spec's table T (C-0's octave in 16.16) shifted per octave, N = (T[i] + (1 << (15 + o))) >> (16 + o).
     */
    fun period(pitch: Int): Int {
        val p = pitch.coerceIn(PcePlayerImage.PITCH_MIN, PcePlayerImage.PITCH_MAX)
        val o = p / 768
        val t = periodQ16(p % 768)
        return ((t + (1L shl (15 + o))) shr (16 + o)).toInt()
    }

    /**
     * `PCP_PERIOD_Q16[i]`: round(39375000 / 11 / (32 · 440 · 2^((i / 64 − 57) / 12)) · 65536) in double precision, the
     * spec's formula with the exact clock (a rounded decimal clock changes 4 entries).
     */
    fun periodQ16(i: Int): Long = (39_375_000.0 / 11 / (32 * 440.0 * 2.0.pow((i / 64.0 - 57) / 12)) * 65536).roundToLong()

    /**
     * `pcp_sample_step`: frames per timer interrupt in 16.16 for a sample sound with root note [root] playing [pitch]
     * (1/64 semitone): Q(root · 64) / Q(pitch) · 65536 with Q(p) = T[p mod 768] / 2^(p / 768), in integers (truncating).
     * The root plays 65536, one frame per interrupt.
     */
    fun sampleStep(pitch: Int, root: Int): Long {
        val p = pitch.coerceIn(PcePlayerImage.PITCH_MIN, PcePlayerImage.PITCH_MAX)
        val rp = root * 64
        val d = p / 768 - rp / 768
        val tr = periodQ16(rp % 768)
        val tp = periodQ16(p % 768)
        return if (d >= 0) (tr shl (16 + d)) / tp else ((tr shl 16) / tp) shr -d
    }

    /** The frequency a tone [note] sounds at: 3579545.45 / (32 · N). */
    fun toneHz(note: Int): Double = PSG_HZ / (32 * period(note * 64))

    /** The noise rate (0..31) [note] plays on a noise sound: note mod 32. */
    fun noiseRate(note: Int): Int = note and 31

    /** How often the noise generator steps for [note]: CPU / ((31 − NF) · 128), NF 31: CPU / 64. */
    fun noiseHz(note: Int): Double {
        val nf = noiseRate(note)
        return CPU_HZ / (if (nf == 31) 64 else (31 - nf) * 128)
    }

    /** The CPU timer's prescaler: the reload counter steps every 1024 CPU cycles. */
    const val TIMER_DIVIDER = 1024

    /** The DDA timer's interrupt rate for reload [timer] (0..127): CPU / (1024 · (R + 1)), 6991.3 Hz at 0. */
    fun timerHz(timer: Int): Double = CPU_HZ / (TIMER_DIVIDER * (timer.coerceIn(0, PceSound.MAX_TIMER) + 1))

    /** The DDA rate at reload [timer] in whole Hz, as people set it: "6991 Hz", "3496 Hz". */
    fun ddaText(timer: Int): String = NumberText.fixed(timerHz(timer), 0) + " Hz"

    /**
     * The LFO's cycle rate with PSG 2 sounding [note] and LFO frequency [frequency] (0 = 256): PSG 2's wave steps
     * [frequency] times slower than its note, so one LFO cycle = the note's frequency ÷ the frequency.
     */
    fun lfoHz(note: Int, frequency: Int): Double = toneHz(note) / (if (frequency == 0) 256 else frequency)

    /** The LFO depth names of register 9 bits 0-1: PSG 2's wave (−16..15) shifted left 0, 2 or 4 onto PSG 1's period. */
    val LFO_DEPTH_NAMES = listOf("Off", "Shallow (period ±16)", "Medium (period ±64)", "Deep (period ±256)")

    /** Register 9 [value] in words: "Off", "Medium (±64)", with ", halted" when bit 7 is set. */
    fun lfoControlText(value: Int): String {
        val depth = LFO_DEPTH_NAMES[value and 3]
        return if (value and LFO_HALT != 0) "$depth, halted" else depth
    }

    /** A frequency as people read it: "0.42 Hz", "5.72 Hz", "59.1 Hz", "440 Hz", "7.0 kHz". */
    fun hzText(hz: Double): String = when {
        hz >= 1000 -> NumberText.fixed(hz / 1000, 1) + " kHz"
        hz >= 100 -> NumberText.fixed(hz, 0) + " Hz"
        hz >= 10 -> NumberText.fixed(hz, 1) + " Hz"
        hz >= 1 -> NumberText.fixed(hz, 2) + " Hz"
        else -> NumberText.fixed(hz, 3) + " Hz"
    }

    /** A balance byte as "L 15 · R 15". */
    fun balanceText(value: Int): String = "L ${value shr 4 and 15} · R ${value and 15}"

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note), noise rates too; the next note " +
                "without 0xy plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a held tone or sample channel glides there without restarting; after OFF it starts a new attack. 300 ends portamento.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "The player's own pitch wobble (depth F: ±1.86 semitones), separate from the chip's LFO; 4x0 stops it; " +
                "the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x left, y right, 0-F",
            "The channel's balance (3 dB per step; FF at the start); 800 silences. Applies at once to the sounding note.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the channel volume every tick until A00 (A01: full to silent in 4.1 s; A0F in 0.3 s).", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-1F",
            "Channel volume from this row on (start 1F), 1.5 dB per step below 1F, on top of the volume envelope; " +
                "sounding notes follow.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, sound and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_LFO_FREQ, "LFO frequency", "xx: 00-FF (00 = 256)",
            "The chip's one LFO: PSG 2's wave steps xx times slower than its note and bends PSG 1's pitch. For every " +
                "channel, from this row on.", true),
        EffectSpec(FX_LFO_CTRL, "LFO control", "xx: 00 off, 01-03 depth, +80 halt",
            "The LFO's depth for PSG 1 (01 shallow, 02 medium, 03 deep; 00 off); 8x halts it and restarts PSG 2's wave. " +
                "PSG 2 keeps sounding: C00 on PSG 2 leaves only the vibrato.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "OFF at tick xx of this row, like an OFF note at that tick.", true),
        EffectSpec(FX_TIMER, "DDA rate", "xx: timer 00-7F",
            "The CPU timer that streams samples, for every channel: 6991 Hz / (xx + 1), from the timer's next reload. " +
                "Sample notes play relative to it.", true),
        EffectSpec(FX_WAVE, "Wave", "xx: wave 00..",
            "The sounding tone's wave (loaded at once) and later notes' until a note row without W; ignored by sounds " +
                "with a wave envelope.", true),
        EffectSpec(FX_MASTER, "Master balance", "xy: x left, y right, 0-F",
            "The chip's master volume per side (3 dB per step, FF at the start), for every channel.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /**
     * Why effect [code]/[param] cannot load (the checks `pc_load_image` makes), given the order count and the song's
     * wave count; or null.
     */
    fun paramProblem(code: Int, param: Int, orders: Int, waves: Int): String? {
        val fx = effectText(code, param)
        if (effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the PC Engine"
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > MAX_VOLUME) "$fx: volumes are C00-C1F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_LFO_CTRL -> if (!lfoControlValid(param)) "$fx: LFO control is M00-M03 or M80-M83" else null
            FX_TIMER -> if (param > PceSound.MAX_TIMER) "$fx: DDA timer values are T00-T7F" else null
            FX_WAVE -> when {
                waves == 0 -> "$fx: the song has no waves; add one in the Wave view"
                param >= waves -> "$fx: the song's waves are W00-W${NumberText.hex(waves - 1, 2)}"
                else -> null
            }
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and channel, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) {
            return "note ${cell.note} is outside A-0..B-7 (${NOTES.first}-${NOTES.last}), the notes the player plays"
        }
        if (cell.instrument !in 0..PceSystem.SLOTS) return "sound ${cell.instrument} is outside 0-${PceSystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the PC Engine player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the PC Engine"
        }
        return null
    }

    /** Human-readable meaning of [effect]/[param] for the inspector and picker; [song] names waves and the LFO rate. */
    fun describe(song: Song?, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_PAN -> if (param == 0) "Pan: silent (balance 0 both sides)" else "Pan: left $x, right $y of 15"
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> if (param > MAX_VOLUME) "Volume: invalid (00-1F)" else "Volume $hex of 1F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_SPEED -> if (param == 0) "Speed: invalid (01-FF)" else "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_LFO_FREQ -> "LFO frequency ${if (param == 0) 256 else param}: PSG 2's wave ${hzText(lfoHz(57, param))} " +
                "at A-4, every channel"
            FX_LFO_CTRL -> if (!lfoControlValid(param)) "LFO control: invalid (00-03, 80-83)" else "LFO ${lfoControlText(param).lowercase()}, every channel"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_TIMER -> if (param > PceSound.MAX_TIMER) "DDA rate: invalid (00-7F)" else "DDA rate ${ddaText(param)}, every channel"
            FX_WAVE -> {
                val waves = (song?.sound as? PceSound)?.waves?.size
                when {
                    waves != null && param >= waves -> "Wave $hex: the song has no such wave"
                    else -> "Wave $hex for the sounding tone and later notes"
                }
            }
            FX_MASTER -> "Master balance: left $x, right $y of 15, every channel"
            else -> "Unknown effect"
        }
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = kotlin.math.round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }
}
