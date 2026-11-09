package org.thortracker.system.ym2612

import org.thortracker.backend.YmPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec

/**
 * YM2612 (Mega Drive) cell semantics: Thor Tracker's own player streamed through SGDK's XGM2 driver
 * (docs/AUDIO_ENGINE.md, "YM2612: Effects"), up to [EFFECT_COLUMNS] effect columns per channel, the OPL3's letters
 * where meanings match. FM locks (`E`, `I`, `M`) and the noise lock (`N`) shape the note keyed on their row; volume,
 * pan and the LFO change at once. Channels 0-5 are FM 1-6, 6-8 PSG 1-3, 9 the Noise channel and 10-12 PCM 1-3, the
 * driver's three PCM channels (one-shot samples in FM 6's DAC: only [PCM_EFFECTS] play there).
 */
object YmRules {
    /** Absolute notes the player takes: C-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES = 0 until YmPlayerImage.NOTES

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = YmPlayerImage.MAX_EFFECTS

    const val CHANNELS = YmPlayerImage.CHANNELS
    const val TONAL_CHANNELS = YmPlayerImage.TONAL_CHANNELS
    const val FM_CHANNELS = YmPlayerImage.FM_CHANNELS
    const val FM3 = YmPlayerImage.CH_FM3
    const val FM6 = YmPlayerImage.CH_FM6
    const val PSG_FIRST = YmPlayerImage.CH_PSG
    const val NOISE = YmPlayerImage.CH_NOISE
    const val PCM_FIRST = YmPlayerImage.CH_PCM
    const val PCM_CHANNELS = YmPlayerImage.PCM_CHANNELS

    val CHANNEL_NAMES = (1..FM_CHANNELS).map { "FM $it" } + (1..3).map { "PSG $it" } + "Noise" + (1..PCM_CHANNELS).map { "PCM $it" }

    /** The lowest note the PSG tones reach: below A-2 the 10-bit period clamps at 1023 (109.3 Hz). */
    const val PSG_LOWEST = 45

    fun isFm(channel: Int) = channel in 0 until FM_CHANNELS

    /** PSG 1-3 and the Noise channel. */
    fun isPsg(channel: Int) = channel in PSG_FIRST..NOISE

    /** PCM 1-3. */
    fun isPcm(channel: Int) = channel in PCM_FIRST until PCM_FIRST + PCM_CHANNELS

    // Effect codes: the player's (generated from ym_image.h).
    const val FX_ARPEGGIO = YmPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = YmPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = YmPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = YmPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = YmPlayerImage.FX_VIBRATO
    const val FX_PAN = YmPlayerImage.FX_PAN
    const val FX_VOLUME_SLIDE = YmPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = YmPlayerImage.FX_JUMP
    const val FX_VOLUME = YmPlayerImage.FX_VOLUME
    const val FX_BREAK = YmPlayerImage.FX_BREAK
    const val FX_FEEDBACK = YmPlayerImage.FX_FEEDBACK
    const val FX_SPEED = YmPlayerImage.FX_SPEED
    const val FX_DELAY = YmPlayerImage.FX_DELAY
    const val FX_BRIGHTNESS = YmPlayerImage.FX_BRIGHTNESS
    const val FX_LFO = YmPlayerImage.FX_LFO
    const val FX_MULT = YmPlayerImage.FX_MULT
    const val FX_NOISE = YmPlayerImage.FX_NOISE
    const val FX_FINE = YmPlayerImage.FX_FINE
    const val FX_CUT = YmPlayerImage.FX_CUT

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** Effects that act only on the note keyed on their row (parameter locks). */
    val NOTE_EFFECTS = setOf(FX_FEEDBACK, FX_BRIGHTNESS, FX_MULT, FX_NOISE)

    /** Effects only the FM channels play. */
    val FM_ONLY = setOf(FX_PAN, FX_FEEDBACK, FX_BRIGHTNESS, FX_MULT)

    /**
     * What PCM 1-3 play besides notes and OFF: the song's flow (B, D, F), the chip's LFO (L), note delay (G) and stop
     * (S). A driver sample cannot change once it plays, so no volume, pitch or pan effect exists there.
     */
    val PCM_EFFECTS = setOf(FX_JUMP, FX_BREAK, FX_SPEED, FX_DELAY, FX_LFO, FX_CUT)

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
            "Wobble the pitch (depth F: ±1.9 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x≠0 left, y≠0 right (FM)",
            "FM output left (10), right (01) or both (11); 800 silences. Applies at once. FM 6's pan also pans PCM 1-3 (the DAC).", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 step per tick)",
            "Change the channel volume every tick until A00.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: FM 00-7F, PSG 00-0F",
            "Channel volume from this row on (start 7F on FM, 0F on PSG); FM 0.75 dB per step on the carriers.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_FEEDBACK, "Feedback lock", "xx: 0-7 (FM)",
            "Operator 1 feedback for the note on this row only.", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed. The row's " +
                "own Sxx still counts from the row's start: one before the delay never fires.", true),
        EffectSpec(FX_BRIGHTNESS, "Brightness lock", "xx: 00-7F (FM)",
            "Every modulator's level = 7F − xx for the note on this row (7F brightest); algorithm 7 has none.", true),
        EffectSpec(FX_LFO, "LFO", "xx: 00 off, 08-0F on at rate 0-7",
            "The chip's LFO from this row on (all channels): rates 3.98, 5.56, 6.02, 6.37, 6.88, 9.63, 48.1, 72.2 Hz. " +
                "Instruments set how deep (AMS/FMS, operator AM).", true),
        EffectSpec(FX_MULT, "Ratio lock", "xy: operator x 1-4, multiplier y 0-F (FM)",
            "Operator x's frequency multiplier (0 = ×½) for the note on this row; detune kept.", true),
        EffectSpec(FX_NOISE, "Noise mode", "xy: x 0 periodic / 1 white, y rate 0-3 (Noise)",
            "Noise for the note on this row: rate 0-2 = clock/512, /1024, /2048; 3 = pitched by the note (PSG 3 goes silent).", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Key off at tick xx of this row (the release plays), like an OFF at that tick; on PCM 1-3 the sample stops.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    private val FM_EFFECTS = EFFECTS.filter { it.code != FX_NOISE }
    private val PSG_EFFECTS = EFFECTS.filter { it.code !in FM_ONLY && it.code != FX_NOISE }
    private val NOISE_EFFECTS = EFFECTS.filter { it.code !in FM_ONLY }
    private val PCM_EFFECT_SPECS = EFFECTS.filter { it.code in PCM_EFFECTS }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** Effects [channel] plays (the loader rejects the others there). */
    fun effectsFor(channel: Int): List<EffectSpec> = when {
        isFm(channel) -> FM_EFFECTS
        channel == NOISE -> NOISE_EFFECTS
        isPcm(channel) -> PCM_EFFECT_SPECS
        else -> PSG_EFFECTS
    }

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load on [channel] (the checks `ym_load_image` makes), given the order count; or null. */
    fun paramProblem(channel: Int, code: Int, param: Int, orders: Int): String? {
        val x = param shr 4
        val y = param and 0x0F
        val fx = effectText(code, param)
        val fm = isFm(channel)
        if (effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the Mega Drive"
        if (isPcm(channel) && code !in PCM_EFFECTS) {
            return "$fx does not play on PCM lanes: an XGM2 sample cannot change once it starts. PCM 1-3 take notes, OFF, " +
                "Gxx, Sxx and the song's Bxx, Dxx, Fxx and Lxx; set loudness with the kit's level"
        }
        if (!fm && code in FM_ONLY) return "$fx plays on FM channels only"
        if (code == FX_NOISE && channel != NOISE) return "$fx sets the noise mode; it plays on the Noise channel only"
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (fm && param > 0x7F) "$fx: FM volumes are C00-C7F"
                else if (!fm && param > 0x0F) "$fx: PSG volumes are C00-C0F" else null
            FX_FEEDBACK -> if (param > 7) "$fx: feedback is E00-E07" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_BRIGHTNESS -> if (param > 0x7F) "$fx: brightness is I00-I7F" else null
            FX_LFO -> if (param != 0 && param !in 0x08..0x0F) "$fx: the LFO is L00 (off) or L08-L0F (rate 0-7)" else null
            FX_MULT -> if (x !in 1..4) "$fx: the operator digit is 1-4" else null
            FX_NOISE -> if (x > 1 || y > 3) "$fx: x is 0 (periodic) or 1 (white), y the rate 0-3" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and channel, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) return "note ${cell.note} is outside C-0..B-7 (0-95)"
        if (cell.instrument !in 0..YmSystem.SLOTS) return "instrument ${cell.instrument} is outside 0-${YmSystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the Mega Drive player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the Mega Drive"
        }
        return null
    }

    /** LFO rates of register $22 (rate 0..7), in Hz, as the chip's manual gives them. */
    val LFO_HZ = listOf("3.98", "5.56", "6.02", "6.37", "6.88", "9.63", "48.1", "72.2")

    /** Noise-mode text: "white /1024", "periodic pitched". */
    fun noiseText(mode: Int): String {
        val kind = if (mode and 4 != 0) "white" else "periodic"
        return "$kind ${NOISE_RATES[mode and 3]}"
    }

    /** The four noise sources: three fixed clock dividers and PSG 3's period. */
    val NOISE_RATES = listOf("/512", "/1024", "/2048", "pitched")

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
            FX_PAN -> when {
                x != 0 && y != 0 -> "Pan centre (both outputs)"
                x != 0 -> "Pan left"
                y != 0 -> "Pan right"
                else -> "Pan: silent (no output)"
            }
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> if (isFm(channel)) "Volume $hex of 7F" else "Volume $hex of 0F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_FEEDBACK -> "Feedback $param for this note"
            FX_SPEED -> "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_BRIGHTNESS -> "Brightness $hex of 7F for this note"
            FX_LFO -> if (param == 0) "LFO off" else if (param in 0x08..0x0F) "LFO on, rate ${param - 8} (${LFO_HZ[param - 8]} Hz)" else "LFO: invalid value"
            FX_MULT -> "Operator $x ratio ${YmCurated.multText(y)} for this note"
            FX_NOISE -> if (x > 1 || y > 3) "Noise mode: invalid" else "Noise ${noiseText((x shl 2) or y)} for this note"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            else -> "Unknown effect"
        }
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = kotlin.math.round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }
}
