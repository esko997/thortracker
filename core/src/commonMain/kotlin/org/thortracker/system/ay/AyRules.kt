package org.thortracker.system.ay

import org.thortracker.backend.AyPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec

/**
 * AY-3-8910 / YM2149 cell semantics: Thor Tracker's own player (docs/AUDIO_ENGINE.md, "AY-3-8910 / YM2149: Effects"),
 * up to [EFFECT_COLUMNS] effect columns per channel, every effect on every channel, the Mega Drive's letters where
 * meanings match. The note settings ([NOTE_SETTINGS]: `E`, `H`, `I`, `J`, `V`, `W`) shape the note keyed on their row
 * (on a row without a key-on they change the sounding note at once); volume, slides and vibrato act from their row on.
 * Also the period arithmetic the player uses ([tonePeriod], [autoEnvelopePeriod], [syncedTonePeriod]), from the
 * variant's generated divider table.
 */
object AyRules {
    /** Absolute notes the player takes: C-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES = 0 until AyPlayerImage.NOTES

    const val CHANNELS = AyPlayerImage.CHANNELS

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = AyPlayerImage.MAX_EFFECTS

    val CHANNEL_NAMES = listOf("Channel A", "Channel B", "Channel C")

    /** One-letter channel names for compact messages and tags. */
    val CHANNEL_LETTERS = listOf("A", "B", "C")

    // Effect codes: the player's (generated from ay_image.h).
    const val FX_ARPEGGIO = AyPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = AyPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = AyPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = AyPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = AyPlayerImage.FX_VIBRATO
    const val FX_VOLUME_SLIDE = AyPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = AyPlayerImage.FX_JUMP
    const val FX_VOLUME = AyPlayerImage.FX_VOLUME
    const val FX_BREAK = AyPlayerImage.FX_BREAK
    const val FX_ENV_SHAPE = AyPlayerImage.FX_ENV_SHAPE
    const val FX_SPEED = AyPlayerImage.FX_SPEED
    const val FX_DELAY = AyPlayerImage.FX_DELAY
    const val FX_ENV_HIGH = AyPlayerImage.FX_ENV_HIGH
    const val FX_ENV_LOW = AyPlayerImage.FX_ENV_LOW
    const val FX_ENV_AUTO = AyPlayerImage.FX_ENV_AUTO
    const val FX_FINE = AyPlayerImage.FX_FINE
    const val FX_CUT = AyPlayerImage.FX_CUT
    const val FX_MIXER = AyPlayerImage.FX_MIXER
    const val FX_NOISE = AyPlayerImage.FX_NOISE

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** The note settings: collected per row and applied to the note keyed there (or the sounding note at once). */
    val NOTE_SETTINGS = setOf(FX_ENV_SHAPE, FX_ENV_HIGH, FX_ENV_LOW, FX_ENV_AUTO, FX_MIXER, FX_NOISE)

    // Value ranges of the instrument fields and envelopes.
    val VOLUME = 0..15
    val MIXER = 0..3
    val NOISE = 0..AyPlayerImage.NOISE_PERIOD_MAX
    val ARPEGGIO = -64..63
    val PITCH = -64..63
    val OCTAVES = AyPlayerImage.OCTAVE_MIN..AyPlayerImage.OCTAVE_MAX
    val PERIOD = 0..AyPlayerImage.ENV_PERIOD_MAX

    /** The hardware envelope shapes a sound or `Exx` may choose (0 = none). */
    val SHAPES = 8..15

    /** Mixer bits: tone (bit 0) and noise (bit 1) gates. */
    const val MIXER_TONE = 1
    const val MIXER_NOISE = 2

    /** What a channel's mixer plays without a mixer envelope: tone only. */
    const val MIXER_DEFAULT = MIXER_TONE

    /** The shapes' level drawings, as the manuals draw them (8 `\\\\` … F `/___`). */
    val SHAPE_GLYPHS: Map<Int, String> = mapOf(
        8 to "\\\\\\\\", 9 to "\\___", 10 to "\\/\\/", 11 to "\\‾‾‾",
        12 to "////", 13 to "/‾‾‾", 14 to "/\\/\\", 15 to "/___",
    )

    /** Each shape's drawing and name (8..15). */
    val SHAPE_NAMES: Map<Int, String> = mapOf(
        8 to "${SHAPE_GLYPHS[8]} saw down",
        9 to "${SHAPE_GLYPHS[9]} decay",
        10 to "${SHAPE_GLYPHS[10]} triangle",
        11 to "${SHAPE_GLYPHS[11]} decay, then high",
        12 to "${SHAPE_GLYPHS[12]} saw up",
        13 to "${SHAPE_GLYPHS[13]} attack, then hold",
        14 to "${SHAPE_GLYPHS[14]} triangle up",
        15 to "${SHAPE_GLYPHS[15]} attack, then silent",
    )

    /** True for the shapes that repeat (saws and triangles): the envelope sounds as a tone (a "buzzer"). */
    fun periodic(shape: Int): Boolean = shape == 8 || shape == 10 || shape == 12 || shape == 14

    /** True for the triangles (two ramps per cycle). */
    fun triangle(shape: Int): Boolean = shape == 10 || shape == 14

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note); the next note without 0xy plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding channel glides there without a new attack; 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "Wobble the pitch (depth F: ±1.9 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the channel volume every tick until A00.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Channel volume from this row on (start 0F); it scales the sound's level. C00 silences, a hardware-envelope note too.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_ENV_SHAPE, "Envelope shape", "xx: 00 off, 08-0F shape",
            "The hardware envelope shape for the note on this row (8 \\\\\\\\, 9 \\___, A \\/\\/, B \\‾‾, C ////, D /‾‾, E /\\/\\, " +
                "F /___); a change restarts the envelope. The chip has one envelope: the latest note using it owns it.", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_ENV_HIGH, "Envelope period high", "xx: 00-FF",
            "Manual envelope period for the note on this row: the high byte (the low byte kept).", true),
        EffectSpec(FX_ENV_LOW, "Envelope period low", "xx: 00-FF",
            "Manual envelope period for the note on this row: the low byte (the high byte kept).", true),
        EffectSpec(FX_ENV_AUTO, "Auto envelope", "xx: 00-07 up, F8-FF down",
            "The envelope period follows the note on this row, xx octaves up (J00: the note's own pitch).", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Key off at tick xx of this row (the release plays), like an OFF at that tick.", true),
        EffectSpec(FX_MIXER, "Mixer", "xx: 00-03 (1 tone, 2 noise)",
            "Tone and noise gates for the note on this row: 01 tone, 02 noise, 03 both, 00 neither (the bare envelope: the buzzer).", true),
        EffectSpec(FX_NOISE, "Noise period", "xx: 00-1F",
            "The noise period the note on this row asks for. The chip has one noise generator: the latest note asking owns it.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** Every channel plays every effect. */
    fun effectsFor(channel: Int): List<EffectSpec> = EFFECTS

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load (the checks `ay_load_image` makes), given the order count; or null. */
    fun paramProblem(channel: Int, code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        if (effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the AY"
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > 0x0F) "$fx: volumes are C00-C0F" else null
            FX_ENV_SHAPE -> if (param != 0 && param !in SHAPES) "$fx: shapes are E00 (off) and E08-E0F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_ENV_AUTO -> if (param !in 0x00..0x07 && param !in 0xF8..0xFF) "$fx: octaves are J00-J07 (up) and JF8-JFF (down)" else null
            FX_MIXER -> if (param > 3) "$fx: the mixer is V00-V03 (bit 0 tone, bit 1 noise)" else null
            FX_NOISE -> if (param > AyPlayerImage.NOISE_PERIOD_MAX) "$fx: noise periods are W00-W1F" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and channel, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) return "note ${cell.note} is outside C-0..B-7 (0-95)"
        if (cell.instrument !in 0..AySystem.SLOTS) return "instrument ${cell.instrument} is outside 0-${AySystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the AY player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the AY"
        }
        return null
    }

    /** `Jxx`'s parameter as the octave offset −8..7. */
    fun octaveOf(param: Int): Int = param.toByte().toInt()

    /** The `Jxx` parameter of octave offset [octave] (−8..7). */
    fun octaveParam(octave: Int): Int = octave and 0xFF

    /** "J00", "JFE": an auto octave as its effect. */
    fun autoText(octave: Int): String = "J${NumberText.hex(octaveParam(octave), 2)}"

    /** "shape C": a shape by its digit. */
    fun shapeText(shape: Int): String = if (shape == 0) "no envelope" else "shape ${NumberText.hex(shape)}"

    /** Human-readable meaning of [effect]/[param] for the inspector and picker. */
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
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> if (param > 0x0F) "Volume: invalid (00-0F)" else "Volume $hex of 0F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_ENV_SHAPE -> when {
                param == 0 -> "Envelope off for this note (fixed volume)"
                param in SHAPES -> "Envelope shape ${NumberText.hex(param)} ${SHAPE_NAMES[param]} for this note"
                else -> "Envelope shape: invalid (E00, E08-E0F)"
            }
            FX_SPEED -> "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_ENV_HIGH -> "Manual envelope period high byte $hex for this note"
            FX_ENV_LOW -> "Manual envelope period low byte $hex for this note"
            FX_ENV_AUTO -> if (param in 0x00..0x07 || param in 0xF8..0xFF) {
                val o = octaveOf(param)
                if (o == 0) "Auto envelope at the note's pitch for this note"
                else "Auto envelope ${kotlin.math.abs(o)} octave${if (kotlin.math.abs(o) == 1) "" else "s"} ${if (o > 0) "up" else "down"} for this note"
            } else "Auto envelope: invalid (J00-J07, JF8-JFF)"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_MIXER -> if (param > 3) "Mixer: invalid (V00-V03)" else "Mixer ${mixerText(param)} for this note"
            FX_NOISE -> if (param > AyPlayerImage.NOISE_PERIOD_MAX) "Noise period: invalid (W00-W1F)" else "Noise period $param for this note"
            else -> "Unknown effect"
        }
    }

    /** "tone", "noise", "tone + noise", "neither": a mixer value's gates. */
    fun mixerText(mixer: Int): String = when (mixer and 3) {
        MIXER_TONE -> "tone"
        MIXER_NOISE -> "noise"
        MIXER_TONE or MIXER_NOISE -> "tone + noise"
        else -> "neither"
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = kotlin.math.round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }

    // ---- periods (docs/AUDIO_ENGINE.md, "AY-3-8910 / YM2149: Pitch") ----------------------------------------------

    /** Pitch units per octave (1/64 semitone). */
    private const val OCTAVE_UNITS = 12 * AyPlayerImage.PITCH_UNITS_PER_SEMITONE

    /** The variant's divider table `AY_DIV[v]`, 768 entries per octave (read once; the generated getters allocate). */
    private val DIV: Array<IntArray> = Array(AyVariant.entries.size) { i ->
        when (i) {
            AyPlayerImage.VARIANT_ZX128 -> AyPlayerImage.DIV_ZX128
            AyPlayerImage.VARIANT_MSX -> AyPlayerImage.DIV_MSX
            AyPlayerImage.VARIANT_ATARI_ST -> AyPlayerImage.DIV_ATARI_ST
            else -> AyPlayerImage.DIV_CPC
        }
    }

    private fun div(variant: AyVariant, f: Int): Long = DIV[variant.index][f].toLong()

    /** The tone period the player writes for [pitch] (1/64 semitone from C-0, clamped 0..6143): 1..4095. */
    fun tonePeriod(variant: AyVariant, pitch: Int): Int = rawTonePeriod(variant, pitch).coerceIn(1L, AyPlayerImage.TONE_PERIOD_MAX.toLong()).toInt()

    private fun rawTonePeriod(variant: AyVariant, pitch: Int): Long {
        val p = pitch.coerceIn(0, AyPlayerImage.PITCH_MAX)
        val o = p / OCTAVE_UNITS
        return (div(variant, p % OCTAVE_UNITS) + (128L shl o)) shr (8 + o)
    }

    /**
     * The envelope period an auto-mode note writes: its audible cycle sounds [pitch] [octave] (−8..7) octaves up — saw
     * shapes 8 and 12 repeat every 256 · EP chip clocks, the triangles 10 and 14 every 512 · EP. 1..65535.
     */
    fun autoEnvelopePeriod(variant: AyVariant, pitch: Int, octave: Int, shape: Int): Int {
        val p = pitch.coerceIn(0, AyPlayerImage.PITCH_MAX)
        val o = p / OCTAVE_UNITS
        val s = 8 + o + 4 + (if (triangle(shape)) 1 else 0) + octave
        return ((div(variant, p % OCTAVE_UNITS) + (1L shl (s - 1))) shr s).coerceIn(1L, AyPlayerImage.ENV_PERIOD_MAX.toLong()).toInt()
    }

    /**
     * The synced tone period (auto mode with sync): [envPeriod] · 2^k, k = 4 + t + [octave] (t = 1 for the triangles),
     * so tone and envelope share one exact ratio. 1..4095.
     */
    fun syncedTonePeriod(variant: AyVariant, envPeriod: Int, octave: Int, shape: Int): Int {
        val k = 4 + (if (triangle(shape)) 1 else 0) + octave
        val ep = envPeriod.toLong()
        val tp = if (k >= 0) ep shl k else (ep + (1L shl (-k - 1))) shr -k
        return tp.coerceIn(1L, AyPlayerImage.TONE_PERIOD_MAX.toLong()).toInt()
    }

    private val LOWEST = IntArray(AyVariant.entries.size) { -1 }

    /** The lowest note whose tone period fits 12 bits (ZX Spectrum 128 and MSX: A-0, Atari ST: B-0, Amstrad CPC: C-0). */
    fun lowestNote(variant: AyVariant): Int {
        val cached = LOWEST[variant.index]
        if (cached >= 0) return cached
        val n = NOTES.first { rawTonePeriod(variant, it * AyPlayerImage.PITCH_UNITS_PER_SEMITONE) <= AyPlayerImage.TONE_PERIOD_MAX }
        LOWEST[variant.index] = n
        return n
    }

    /** The frequency a tone [period] sounds (period 0 acts as 1). */
    fun toneHz(variant: AyVariant, period: Int): Double = variant.clockHz / (16.0 * maxOf(1, period))

    /**
     * How often the envelope at [period] repeats with [shape]: a saw's ramp (256 · EP clocks), a triangle's two ramps
     * (512 · EP); for the one-shot shapes, the rate of their single ramp. EP 0 takes one clock/8 step per level:
     * twice EP 1's rate on the 16-level AY, the same rate on the 32-level YM.
     */
    fun envelopeHz(variant: AyVariant, period: Int, shape: Int): Double {
        val clocksPerStep = 8 * maxOf(1, period * (if (variant.ym) 1 else 2))
        return variant.clockHz / (clocksPerStep.toDouble() * (if (variant.ym) 32 else 16) * (if (triangle(shape)) 2 else 1))
    }
}
