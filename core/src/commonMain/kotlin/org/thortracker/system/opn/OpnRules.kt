package org.thortracker.system.opn

import org.thortracker.backend.OpnPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.system.EffectSpec
import org.thortracker.system.ym2612.YmCurated
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * YM2203 / YM2608 cell semantics: Thor Tracker's own OPN player (docs/AUDIO_ENGINE.md, "OPN and OPNA"), up to
 * [EFFECT_COLUMNS] effect columns per channel, the Mega Drive's letters where meanings match. Which effects a channel
 * loads depends on its part (FM, SSG, rhythm drum or ADPCM-B) and on the chip: the YM2608's stereo pans (`8xy`), LFO
 * (`Lxx`) and rhythm total level (`Rxx`) do not exist on the YM2203. Global effects (`B`, `D`, `F`, `L`, `R`, `T`) load
 * on every channel. [paramProblem] is exactly what `opn_load_image` rejects, as user-facing messages.
 */
object OpnRules {
    /** Absolute notes the player takes: C-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES = 0 until OpnPlayerImage.NOTES

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = OpnPlayerImage.MAX_EFFECTS

    /** The SSG's lowest note: below B-0 the 12-bit tone period would pass 4095. */
    const val SSG_LOWEST = OpnPlayerImage.SSG_LOWEST

    const val FX_ARPEGGIO = OpnPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = OpnPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = OpnPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = OpnPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = OpnPlayerImage.FX_VIBRATO
    const val FX_PAN = OpnPlayerImage.FX_PAN
    const val FX_VOLUME_SLIDE = OpnPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = OpnPlayerImage.FX_JUMP
    const val FX_VOLUME = OpnPlayerImage.FX_VOLUME
    const val FX_BREAK = OpnPlayerImage.FX_BREAK
    const val FX_FEEDBACK = OpnPlayerImage.FX_FEEDBACK
    const val FX_SPEED = OpnPlayerImage.FX_SPEED
    const val FX_DELAY = OpnPlayerImage.FX_DELAY
    const val FX_ENV_SHAPE = OpnPlayerImage.FX_ENV_SHAPE
    const val FX_BRIGHTNESS = OpnPlayerImage.FX_BRIGHTNESS
    const val FX_ENV_PERIOD = OpnPlayerImage.FX_ENV_PERIOD
    const val FX_LFO = OpnPlayerImage.FX_LFO
    const val FX_MULT = OpnPlayerImage.FX_MULT
    const val FX_NOISE = OpnPlayerImage.FX_NOISE
    const val FX_FINE = OpnPlayerImage.FX_FINE
    const val FX_RHYTHM_LEVEL = OpnPlayerImage.FX_RHYTHM_LEVEL
    const val FX_CUT = OpnPlayerImage.FX_CUT
    const val FX_TIMER_A = OpnPlayerImage.FX_TIMER_A

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** Effects that shape only the note keyed on their row (parameter locks). */
    val NOTE_EFFECTS = setOf(FX_FEEDBACK, FX_BRIGHTNESS, FX_MULT, FX_ENV_SHAPE, FX_ENV_PERIOD)

    /** Effects that act on the whole chip from their row, whatever channel holds them. */
    val GLOBAL = setOf(FX_JUMP, FX_BREAK, FX_SPEED, FX_LFO, FX_RHYTHM_LEVEL, FX_TIMER_A)

    /** Effects only the YM2608 has. */
    val OPNA_ONLY = setOf(FX_PAN, FX_LFO, FX_RHYTHM_LEVEL)

    /** Effects each part loads (global effects aside); [OPNA_ONLY] ones only on the YM2608. */
    private val PART_EFFECTS = mapOf(
        OpnPart.FM to setOf(FX_ARPEGGIO, FX_SLIDE_UP, FX_SLIDE_DOWN, FX_PORTAMENTO, FX_VIBRATO, FX_PAN, FX_VOLUME_SLIDE,
            FX_VOLUME, FX_FEEDBACK, FX_DELAY, FX_BRIGHTNESS, FX_MULT, FX_FINE, FX_CUT),
        OpnPart.SSG to setOf(FX_ARPEGGIO, FX_SLIDE_UP, FX_SLIDE_DOWN, FX_PORTAMENTO, FX_VIBRATO, FX_VOLUME_SLIDE, FX_VOLUME,
            FX_DELAY, FX_ENV_SHAPE, FX_ENV_PERIOD, FX_NOISE, FX_FINE, FX_CUT),
        OpnPart.RHYTHM to setOf(FX_PAN, FX_VOLUME_SLIDE, FX_VOLUME, FX_DELAY, FX_CUT),
        OpnPart.ADPCM to setOf(FX_ARPEGGIO, FX_SLIDE_UP, FX_SLIDE_DOWN, FX_PORTAMENTO, FX_VIBRATO, FX_PAN, FX_VOLUME_SLIDE,
            FX_VOLUME, FX_DELAY, FX_FINE, FX_CUT),
    )

    /** LFO rates of register `$22` (rate 0..7), in Hz, as the chip's manual gives them. */
    val LFO_HZ = listOf("3.98", "5.56", "6.02", "6.37", "6.88", "9.63", "48.1", "72.2")

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note); the next note without 0xy plays plain. FM, SSG and ADPCM.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding channel glides there without a new attack (no locks); 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "Wobble the pitch (depth F: ±1.9 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x≠0 left, y≠0 right (YM2608)",
            "Output left (10), right (01) or both (11); 800 silences. FM: at once. Rhythm and ADPCM: from this row; the next " +
                "note's sound pan replaces it unless an 8xy stands on that note's row.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the channel volume every tick until A00.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: FM 00-7F, SSG 00-0F, rhythm 00-1F, ADPCM 00-FF",
            "Channel volume from this row on (starts at the top of the range); FM 0.75 dB per step on the carriers.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_FEEDBACK, "Feedback lock", "xx: 0-7 (FM)",
            "Operator 1 feedback for the note on this row only.", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_ENV_SHAPE, "Envelope shape lock", "xx: 00 none, 08-0F shape (SSG)",
            "The SSG hardware envelope for the note on this row: 00 plays the volume envelope, 08-0F the generator's shape.", true),
        EffectSpec(FX_BRIGHTNESS, "Brightness lock", "xx: 00-7F (FM)",
            "Every modulator's level = 7F − xx for the note on this row (7F brightest); algorithm 7 has none.", true),
        EffectSpec(FX_ENV_PERIOD, "Envelope period lock", "xx: 01-FF, period xx × 256 (SSG)",
            "A manual hardware envelope period for the note on this row (instead of the sound's).", true),
        EffectSpec(FX_LFO, "LFO", "xx: 00 off, 08-0F on at rate 0-7 (YM2608)",
            "The chip's LFO from this row on (all channels): rates 3.98, 5.56, 6.02, 6.37, 6.88, 9.63, 48.1, 72.2 Hz. " +
                "FM voices set how deep (AMS/FMS, operator AM).", true),
        EffectSpec(FX_MULT, "Ratio lock", "xy: operator x 1-4, multiplier y 0-F (FM)",
            "Operator x's frequency multiplier (0 = ×½) for the note on this row; detune kept.", true),
        EffectSpec(FX_NOISE, "Noise period", "xx: 00-1F (SSG)",
            "The SSG's shared noise period from this row (higher is lower); a sounding note's noise envelope overrides it.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_RHYTHM_LEVEL, "Rhythm level", "xx: 00-3F (YM2608)",
            "The rhythm unit's total level from this row (all six drums; 3F loudest, 0.75 dB steps).", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Key off at tick xx of this row (the release plays), like an OFF at that tick.", true),
        EffectSpec(FX_TIMER_A, "Timer A", "xx: NA = xx × 4",
            "Timer A from this row: in FM 3's CSM mode it keys FM 3 every (1024 − NA) native samples.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    private val FOR = OpnChip.entries.associateWith { chip ->
        (0 until chip.channelCount).map { ch ->
            val allowed = PART_EFFECTS.getValue(chip.part(ch)) + GLOBAL
            EFFECTS.filter { it.code in allowed && (chip.opna || it.code !in OPNA_ONLY) }
        }
    }

    /** Effects [channel] of [chip] plays (the loader rejects the others there); empty for a channel the chip lacks. */
    fun effectsFor(chip: OpnChip, channel: Int): List<EffectSpec> = FOR.getValue(chip).getOrNull(channel) ?: emptyList()

    /** The largest `Cxx` of [part]: FM 7F, SSG 0F, rhythm 1F, ADPCM FF (also the channel's starting volume). */
    fun volumeMax(part: OpnPart): Int = when (part) {
        OpnPart.FM -> 0x7F
        OpnPart.SSG -> 0x0F
        OpnPart.RHYTHM -> 0x1F
        OpnPart.ADPCM -> 0xFF
    }

    /** The lowest note [channel] of [chip] plays: B-0 on the SSG, else C-0. */
    fun lowest(chip: OpnChip, channel: Int): Int = if (chip.part(channel) == OpnPart.SSG) SSG_LOWEST else 0

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load on [channel] of [chip] (the checks `opn_load_image` makes), given the order count; or null. */
    fun paramProblem(chip: OpnChip, channel: Int, code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        val spec = effect(code) ?: return "effect ${EffectSpec.charOf(code)} does not exist on the ${chip.label}"
        if (code in OPNA_ONLY && !chip.opna) return "$fx: the YM2203 has no ${spec.name.lowercase()} (the YM2608 has)"
        val part = chip.part(channel)
        if (effectsFor(chip, channel).none { it.code == code }) return "$fx (${spec.name.lowercase()}) does not play on ${partText(part)} channels"
        val x = param shr 4
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> volumeMax(part).let { max ->
                if (param > max) "$fx: ${partText(part)} volumes are C00-C${NumberText.hex(max, 2)}" else null
            }
            FX_FEEDBACK -> if (param > 7) "$fx: feedback is E00-E07" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_ENV_SHAPE -> if (param != 0 && param !in 0x08..0x0F) "$fx: the envelope shape is H00 (none) or H08-H0F" else null
            FX_BRIGHTNESS -> if (param > 0x7F) "$fx: brightness is I00-I7F" else null
            FX_ENV_PERIOD -> if (param == 0) "J00: the envelope period lock is J01-JFF (× 256)" else null
            FX_LFO -> if (param != 0 && param !in 0x08..0x0F) "$fx: the LFO is L00 (off) or L08-L0F (rate 0-7)" else null
            FX_MULT -> if (x !in 1..4) "$fx: the operator digit is 1-4" else null
            FX_NOISE -> if (param > 0x1F) "$fx: the noise period is N00-N1F" else null
            FX_RHYTHM_LEVEL -> if (param > 0x3F) "$fx: the rhythm level is R00-R3F" else null
            else -> null
        }
    }

    /** "FM", "SSG", "rhythm" or "ADPCM". */
    fun partText(part: OpnPart): String = when (part) {
        OpnPart.FM -> "FM"
        OpnPart.SSG -> "SSG"
        OpnPart.RHYTHM -> "rhythm"
        OpnPart.ADPCM -> "ADPCM"
    }

    /** Range problem of a single cell independent of the song and channel, or null. */
    fun cellRangeProblem(chip: OpnChip, cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) return "note ${cell.note} is outside C-0..B-7 (0-95)"
        if (cell.instrument !in 0..OpnSound.SLOTS) return "sound ${cell.instrument} is outside 0-${OpnSound.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the OPN player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the ${chip.label}"
        }
        return null
    }

    /** Ticks per second at timer B value [timerB]: 55466.67 / (16 × (256 − TB)). */
    fun tickHz(timerB: Int): Double = OpnPlayerImage.tickHz(timerB)

    /** How often timer A fires at value [na] (0..1023): every (1024 − NA) native samples. In CSM mode it keys FM 3. */
    fun timerAHz(na: Int): Double = OpnPlayerImage.NATIVE_RATE / (1024 - na)

    /** Frequency of absolute [note] in 12-TET from A-4 = 440 Hz. */
    fun noteHz(note: Int): Double = 440.0 * 2.0.pow((note - 57) / 12.0)

    /** The SSG tone period (TP, `$00`-`$05`) of absolute [note]: round(124800 / f), 1..4095. */
    fun ssgPeriod(note: Int): Int = (OpnPlayerImage.SSG_CLOCK / 16.0 / noteHz(note)).roundToInt().coerceIn(1, 4095)

    /**
     * The ADPCM-B delta-N (`$109`/`$10A`) playing a sample recorded at [rate] Hz on [note] with a sound whose root is
     * [root]: rate × 65536 / 55466.67 × 2^((note − root) / 12), 1..65535.
     */
    fun deltaN(rate: Int, root: Int, note: Int): Int =
        (rate * 65536.0 / OpnPlayerImage.NATIVE_RATE * 2.0.pow((note - root) / 12.0)).roundToInt().coerceIn(1, 65535)

    /** Human-readable meaning of [effect]/[param] on [channel] of [chip], for the inspector and picker. */
    fun describe(chip: OpnChip, channel: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        val part = if (channel in 0 until chip.channelCount) chip.part(channel) else OpnPart.FM
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_PAN -> when {
                x != 0 && y != 0 -> "Pan centre (both outputs)"
                x != 0 -> "Pan left"
                y != 0 -> "Pan right"
                else -> "Pan: silent (no output)"
            }
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> "Volume $hex of ${NumberText.hex(volumeMax(part), 2)}"
            FX_BREAK -> "Break to row $param of the next order"
            FX_FEEDBACK -> "Feedback $param for this note"
            FX_SPEED -> "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_ENV_SHAPE -> if (param == 0) "No hardware envelope for this note" else if (param in 0x08..0x0F) "Hardware envelope shape ${hex} for this note" else "Envelope shape: invalid"
            FX_BRIGHTNESS -> "Brightness $hex of 7F for this note"
            FX_ENV_PERIOD -> if (param == 0) "Envelope period: invalid" else "Envelope period ${param * 256} for this note"
            FX_LFO -> if (param == 0) "LFO off" else if (param in 0x08..0x0F) "LFO on, rate ${param - 8} (${LFO_HZ[param - 8]} Hz)" else "LFO: invalid value"
            FX_MULT -> "Operator $x ratio ${YmCurated.multText(y)} for this note"
            FX_NOISE -> if (param > 0x1F) "Noise period: invalid" else "Noise period $param"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_RHYTHM_LEVEL -> if (param > 0x3F) "Rhythm level: invalid" else "Rhythm total level $hex of 3F"
            FX_CUT -> "Note off at tick $param"
            FX_TIMER_A -> "Timer A ${param * 4}: ${NumberText.fixed(timerAHz(param * 4), 1)} Hz (CSM rate)"
            else -> "Unknown effect"
        }
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = kotlin.math.round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }
}
