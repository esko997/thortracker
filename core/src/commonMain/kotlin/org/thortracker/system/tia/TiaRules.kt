package org.thortracker.system.tia

import org.thortracker.backend.TiaPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/**
 * Atari 2600 cell semantics and the TIA's honest pitch arithmetic: Thor Tracker's own player on the TIA's two audio
 * channels (docs/AUDIO_ENGINE.md, "Atari 2600"), [EFFECT_COLUMNS] effect columns per channel, FamiTracker letters where
 * meanings match. Every frequency here is what the chip plays: a waveform's fundamental is the audio clock / (its
 * pattern period × (AUDF + 1)), so a note sounds at the nearest divider's frequency, not at 12-TET.
 */
object TiaRules {
    /** Absolute notes the player takes: C-0..B-9 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES: IntRange = TiaPlayerImage.NOTE_MIN..TiaPlayerImage.NOTE_MAX

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = TiaPlayerImage.MAX_EFFECTS

    val CHANNEL_NAMES = (1..TiaPlayerImage.CHANNELS).map { "TIA $it" }

    // Effect codes: the player's (generated from tia_image.h).
    const val FX_ARPEGGIO = TiaPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = TiaPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = TiaPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = TiaPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = TiaPlayerImage.FX_VIBRATO
    const val FX_VOLUME_SLIDE = TiaPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = TiaPlayerImage.FX_JUMP
    const val FX_VOLUME = TiaPlayerImage.FX_VOLUME
    const val FX_BREAK = TiaPlayerImage.FX_BREAK
    const val FX_SPEED = TiaPlayerImage.FX_SPEED
    const val FX_DELAY = TiaPlayerImage.FX_DELAY
    const val FX_FINE = TiaPlayerImage.FX_FINE
    const val FX_CUT = TiaPlayerImage.FX_CUT
    const val FX_WAVEFORM = TiaPlayerImage.FX_WAVEFORM

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** The pitch unit: 1/64 semitone. */
    const val UNITS = TiaPlayerImage.PITCH_UNITS_PER_SEMITONE

    /** Largest AUDF (the 5-bit divider: AUDF + 1 = 1..32). */
    const val AUDF_MAX = TiaPlayerImage.AUDF_MAX

    // ---- waveforms --------------------------------------------------------------------------------------------------

    /** One AUDC value: [short] for lane tags and lists, [long] as the instrument view explains it. */
    data class Waveform(val audc: Int, val short: String, val long: String)

    /** The 16 AUDC values (Wright 1979; pattern periods as Korth lists them). */
    val WAVEFORMS: List<Waveform> = listOf(
        Waveform(0x0, "constant", "constant: AUDV is a 4-bit DC level, no tone"),
        Waveform(0x1, "4-bit poly", "4-bit poly: a buzzy 15-step pattern"),
        Waveform(0x2, "div 15 → 4-bit poly", "div 15 → 4-bit poly: a low rumble"),
        Waveform(0x3, "5-bit → 4-bit poly", "5-bit → 4-bit poly: a rough rumble"),
        Waveform(0x4, "div 2 square", "div 2: pure square, high"),
        Waveform(0x5, "div 2 square", "div 2: pure square, high (as 4)"),
        Waveform(0x6, "div 31 (18/13)", "div 31: pure tone, 18 high and 13 low, the bass"),
        Waveform(0x7, "5-bit poly → div 2", "5-bit poly → div 2: a reedy, uneven tone"),
        Waveform(0x8, "9-bit poly (noise)", "9-bit poly: white noise"),
        Waveform(0x9, "5-bit poly", "5-bit poly: a reedy, uneven tone (as 7)"),
        Waveform(0xA, "div 31 (18/13)", "div 31: pure tone, the bass (as 6)"),
        Waveform(0xB, "constant", "constant: AUDV is a 4-bit DC level, no tone (as 0)"),
        Waveform(0xC, "div 6 square", "div 6: pure square, the melodic middle"),
        Waveform(0xD, "div 6 square", "div 6: pure square (as C)"),
        Waveform(0xE, "div 93", "div 93: pure tone, very low"),
        Waveform(0xF, "5-bit poly div 6", "5-bit poly ÷ 6: a low, gritty tone"),
    )

    /** [audc]'s pitch class (0..6 by period 2, 6, 15, 31, 93, 465, 511), or -1 for the constant waveforms (0, B). */
    fun pitchClass(audc: Int): Int = TiaPlayerImage.CLASS[audc and 15]

    /** [audc]'s pattern period P (the waveform repeats every P × (AUDF + 1) audio clocks), or null when constant. */
    fun period(audc: Int): Int? = pitchClass(audc).takeIf { it >= 0 }?.let { TiaPlayerImage.PERIOD[it] }

    /** "C", "8": an AUDC value as the editor writes it. */
    fun audcText(audc: Int): String = NumberText.hex(audc and 15)

    /** The colour clock of [region]: 39375000 / 11 Hz (NTSC), 3546894 Hz (PAL). */
    fun clockHz(region: TiaRegion): Double = when (region) {
        TiaRegion.NTSC -> TiaPlayerImage.CLOCK_NTSC_NUM.toDouble() / TiaPlayerImage.CLOCK_NTSC_DEN
        TiaRegion.PAL -> TiaPlayerImage.CLOCK_PAL_NUM.toDouble() / TiaPlayerImage.CLOCK_PAL_DEN
    }

    /** Scanlines per frame of [region]: 262, 312. */
    fun lines(region: TiaRegion): Int = if (region == TiaRegion.NTSC) TiaPlayerImage.LINES_NTSC else TiaPlayerImage.LINES_PAL

    /** Player ticks (TV frames) per second: the colour clock / (lines × 228); 59.92 NTSC, 49.86 PAL. */
    fun tickHz(region: TiaRegion): Double = clockHz(region) / (lines(region) * TiaPlayerImage.CLOCKS_PER_LINE)

    /** The audio clock A: the colour clock / 114 (two audio clocks per scanline). */
    fun audioHz(region: TiaRegion): Double = clockHz(region) / TiaPlayerImage.AUDIO_DIVIDER

    /** The fundamental of [audc] at divider [audf] in [region]: A / (P · (AUDF + 1)); null for the constant waveforms. */
    fun frequency(region: TiaRegion, audc: Int, audf: Int): Double? =
        period(audc)?.let { audioHz(region) / (it * (audf.coerceIn(0, AUDF_MAX) + 1)) }

    private fun edges(region: TiaRegion): IntArray =
        if (region == TiaRegion.NTSC) TiaPlayerImage.AUDF_EDGE_NTSC else TiaPlayerImage.AUDF_EDGE_PAL

    /**
     * The AUDF a tonal sound writes for [pitch] (1/64 semitone, 0 = C-0) on waveform [audc] in [region], as the player
     * computes it: the pitch clamped to the player's range, then the smallest k (0..30) with pitch ≥ the k-th edge of
     * the waveform's class (`TIP_AUDF_EDGE`: the lowest pitch nearer, in log frequency, to divider k + 1 than to k + 2),
     * else 31. Null for the constant waveforms (no AUDF write).
     */
    fun audf(region: TiaRegion, audc: Int, pitch: Int): Int? {
        val c = pitchClass(audc)
        if (c < 0) return null
        val p = pitch.coerceIn(TiaPlayerImage.PITCH_MIN, TiaPlayerImage.PITCH_MAX)
        val e = edges(region)
        // AUDF_MAX edges per class (`TIP_EDGES`): edge k separates divider k from k + 1.
        val base = c * AUDF_MAX
        for (k in 0 until AUDF_MAX) if (p >= e[base + k]) return k
        return AUDF_MAX
    }

    /** The AUDF a raw sound writes for [note] plus [semitones]: 31 − ((note + semitones) mod 32). */
    fun rawAudf(note: Int, semitones: Int = 0): Int = AUDF_MAX - (note + semitones).mod(AUDF_MAX + 1)

    /** 12-TET frequency of [note] (A-4 = 57 = 440 Hz). */
    fun noteHz(note: Int): Double = 440.0 * 2.0.pow((note - 57) / 12.0)

    /** How far [hz] is from [note], in cents (positive: sharp). */
    fun cents(hz: Double, note: Int): Double = 1200.0 * ln(hz / noteHz(note)) / ln(2.0)

    /** The note nearest [hz] (may lie outside [NOTES]). */
    fun nearestNote(hz: Double): Int = round(57 + 12 * ln(hz / 440.0) / ln(2.0)).toInt()

    /** Notes counted inside a span are allowed this many cents past its ends (C-2 on the 4-bit poly is −0.3¢ off). */
    private const val REACH_TOLERANCE_CENTS = 1.0

    /**
     * The notes waveform [audc] reaches in [region]: those whose 12-TET frequency lies between its AUDF 31 and AUDF 0
     * frequencies (within a cent); null for the constant waveforms. A tonal note outside plays the nearest end.
     */
    fun reach(region: TiaRegion, audc: Int): IntRange? {
        val low = frequency(region, audc, AUDF_MAX) ?: return null
        val high = frequency(region, audc, 0)!!
        val inside = NOTES.filter { cents(low, it) <= REACH_TOLERANCE_CENTS && cents(high, it) >= -REACH_TOLERANCE_CENTS }
        return if (inside.isEmpty()) null else inside.first()..inside.last()
    }

    /** One rung of a waveform's AUDF ladder: the divider, its frequency, the nearest note and the error in cents. */
    data class Rung(val audf: Int, val hz: Double, val note: Int, val cents: Double)

    /** Waveform [audc]'s 32 dividers in [region], AUDF 0 (highest) first; empty for the constant waveforms. */
    fun ladder(region: TiaRegion, audc: Int): List<Rung> {
        if (period(audc) == null) return emptyList()
        return (0..AUDF_MAX).map { a ->
            val hz = frequency(region, audc, a)!!
            val note = nearestNote(hz)
            Rung(a, hz, note, cents(hz, note))
        }
    }

    /** What a tonal note keys on with: the divider, its frequency and the error against the note. */
    data class Played(val audf: Int, val hz: Double, val cents: Double)

    /**
     * What tonal [note] at [fine] (1/64 semitone, plus [semitones] of arpeggio) plays on [audc] in [region]: the AUDF the
     * player writes, its frequency and the error against the note in cents; null for the constant waveforms.
     */
    fun played(region: TiaRegion, audc: Int, note: Int, fine: Int = 0, semitones: Int = 0): Played? {
        val a = audf(region, audc, (note + semitones) * UNITS + fine) ?: return null
        val hz = frequency(region, audc, a)!!
        return Played(a, hz, cents(hz, note + semitones) - fine * 100.0 / UNITS)
    }

    /** A frequency as people read it: "1.92 Hz", "65.4 Hz", "436.1 Hz", "15.70 kHz". */
    fun hzText(hz: Double): String = when {
        hz >= 1000 -> NumberText.fixed(hz / 1000, 2) + " kHz"
        hz >= 10 -> NumberText.fixed(hz, 1) + " Hz"
        else -> NumberText.fixed(hz, 2) + " Hz"
    }

    /** Cents as "+3¢", "−15.6¢", "±0¢". */
    fun centsText(cents: Double): String {
        val r = round(cents * 10) / 10
        return when {
            abs(r) < 0.05 -> "±0¢"
            r > 0 -> "+" + NumberText.fixed(r, 1) + "¢"
            else -> "−" + NumberText.fixed(-r, 1) + "¢"
        }
    }

    // ---- effects ----------------------------------------------------------------------------------------------------

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note); on raw sounds it steps the " +
                "divider; the next note without 0xy plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; heard each time it crosses to the next " +
                "AUDF divider, so it steps. Tonal sounds only.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300; it steps from divider to divider.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding tonal channel glides there without key-on, stepping through the dividers; " +
                "300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "Pitch wobble (depth F: ±1.86 semitones), heard only where it crosses divider edges; 4x0 stops it.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/8 AUDV step per tick)",
            "Change the channel volume every tick until A00.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Channel volume from this row on (start 0F), taken off the volume envelope: AUDV = envelope + xx − 15.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_SPEED, "Speed", "xx: frames per row 01-FF",
            "Ticks (TV frames) per row from this row on, both channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, sound and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone until the next note (tonal sounds); heard only when it " +
                "moves the note to another divider. Tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "OFF at tick xx of this row, like an OFF note at that tick.", true),
        EffectSpec(FX_WAVEFORM, "Waveform", "xx: AUDC 00-0F",
            "The waveform of the sounding note and of later notes until a note row without W; ignored by sounds with a " +
                "waveform envelope.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load (the checks `ti_load_image` makes), given the order count; or null. */
    fun paramProblem(code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        if (effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the Atari 2600"
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > TiaPlayerImage.VOLUME_MAX) "$fx: volumes are C00-C0F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_WAVEFORM -> if (param > TiaPlayerImage.WAVEFORM_MAX) "$fx: waveforms are W00-W0F (AUDC 0-F)" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and channel, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) {
            return "note ${cell.note} is outside C-0..B-9 (${NOTES.first}-${NOTES.last}), the notes the player plays"
        }
        if (cell.instrument !in 0..TiaSystem.SLOTS) return "sound ${cell.instrument} is outside 0-${TiaSystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the Atari 2600 player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the Atari 2600"
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
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick (steps by divider)"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick (steps by divider)"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y eighths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> if (param > TiaPlayerImage.VOLUME_MAX) "Volume: invalid (00-0F)" else "Volume $hex of 0F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_SPEED -> if (param == 0) "Speed: invalid (01-FF)" else "Speed $param frames per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${if (d > 0) "+$d" else "$d"}/64 semitone (${centsText(d * 100.0 / UNITS)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_WAVEFORM -> if (param > TiaPlayerImage.WAVEFORM_MAX) "Waveform: invalid (00-0F)"
                else "Waveform ${audcText(param)}: ${WAVEFORMS[param].short}, for the sounding and later notes"
            else -> "Unknown effect"
        }
    }
}
