package org.thortracker.system.saa

import org.thortracker.backend.SaaPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * SAA1099 cell semantics: Thor Tracker's own player on one or two Philips SAA1099 (docs/AUDIO_ENGINE.md, "SAA1099:
 * Effects"), up to [EFFECT_COLUMNS] effect columns per channel, FamiTracker letters where meanings match. `0xy`, `8xy`,
 * `Exx`, `Mxx`, `Nxx` and `Pxx` shape the note keyed on their row (or, on a row without a note, the sounding note);
 * volume, slides, vibrato and `Yxx` act from their row on.
 */
object SaaRules {
    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = SaaPlayerImage.MAX_EFFECTS

    // Effect codes: the player's (generated from saa_image.h).
    const val FX_ARPEGGIO = SaaPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = SaaPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = SaaPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = SaaPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = SaaPlayerImage.FX_VIBRATO
    const val FX_PAN = SaaPlayerImage.FX_PAN
    const val FX_VOLUME_SLIDE = SaaPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = SaaPlayerImage.FX_JUMP
    const val FX_VOLUME = SaaPlayerImage.FX_VOLUME
    const val FX_BREAK = SaaPlayerImage.FX_BREAK
    const val FX_ENVELOPE = SaaPlayerImage.FX_ENVELOPE
    const val FX_SPEED = SaaPlayerImage.FX_SPEED
    const val FX_DELAY = SaaPlayerImage.FX_DELAY
    const val FX_MIXER = SaaPlayerImage.FX_MIXER
    const val FX_NOISE = SaaPlayerImage.FX_NOISE
    const val FX_FINE = SaaPlayerImage.FX_FINE
    const val FX_CUT = SaaPlayerImage.FX_CUT
    const val FX_SYNC = SaaPlayerImage.FX_SYNC

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    // `Yxx` values.
    const val SYNC_NORMAL = 0
    const val SYNC_RESTART = 1
    const val SYNC_HOLD = 2
    const val SYNC_OFF = 3

    /** The `Exx` parameters the loader takes besides 00: the control values with bit 7 set and bit 6 clear. */
    val ENVELOPE_CONTROLS = 0x80..0xBF

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Step note, +x, +y every tick for the note on this row (on a row without a note: the sounding note's).", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding channel glides there without a new attack; 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "Wobble the pitch from this row on (depth F: ±1.86 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x left, y right level 0-F",
            "The note on this row plays at left level x and right level y instead of its instrument's pan (8F0 hard left, " +
                "80F hard right); on a row without a note the sounding note's pan changes.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/16 step per tick)",
            "Change the channel volume every tick until A00 (A01: full to silent in 4 s at 60 ticks per second).", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Channel volume from this row on (start 0F); it scales the instrument's volume envelope.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_ENVELOPE, "Hardware envelope", "xx: 00 off, 80-BF control",
            "Lanes 3 and 6 only: the note on this row uses envelope control xx instead of its instrument's (bit 5 " +
                "external clock, bit 4 3-bit, bits 3-1 shape, bit 0 inverted right; E00 off); it restarts the envelope.", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_MIXER, "Mixer", "xx: 00 off, 01 tone, 02 noise, 03 both",
            "The note on this row plays tone, noise, both or neither instead of its instrument's mixer envelope.", true),
        EffectSpec(FX_NOISE, "Noise clock", "xx: 00-03",
            "The note on this row uses noise clock xx (00 fastest, 02 slowest, 03 twice lane 1's or 4's tone) and " +
                "claims its lane triple's noise generator.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Key off at tick xx of this row (a release plays if the instrument has one), like an OFF at that tick.", true),
        EffectSpec(FX_SYNC, "Sync / sound", "xx: 00-03, the whole chip",
            "From this row on, for every lane of this chip: 00 normal, 01 restart every generator in phase, 02 hold " +
                "every generator (silent), 03 sound off.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** "A-0..G#8": [variant]'s note range in names, for messages. */
    fun rangeText(variant: SaaVariant): String =
        "${variant.system.noteName(variant.noteRange.first)}..${variant.system.noteName(variant.noteRange.last)}"

    /** Why effect [code]/[param] cannot load (the checks `sa_load_image` makes), given the song's order count; or null. */
    fun paramProblem(code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > 0x0F) "$fx: volumes are C00-C0F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_ENVELOPE -> if (param != 0 && param !in ENVELOPE_CONTROLS) "$fx: hardware envelope controls are E00 (off) or E80-EBF" else null
            FX_MIXER -> if (param > 3) "$fx: mixer values are M00-M03" else null
            FX_NOISE -> if (param > 3) "$fx: noise clocks are N00-N03" else null
            FX_SYNC -> if (param > 3) "$fx: sync values are Y00-Y03" else null
            else -> null
        }
    }

    /** Range problem of a single cell on [variant] independent of the song, or null. */
    fun cellRangeProblem(cell: Cell, variant: SaaVariant): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in variant.noteRange) {
            return "note ${cell.note} is outside ${rangeText(variant)} (${variant.noteRange.first}-${variant.noteRange.last}), " +
                "the notes the ${variant.label}'s SAA1099 reaches"
        }
        if (cell.instrument !in 0..SaaSound.SLOTS) return "instrument ${cell.instrument} is outside 0-${SaaSound.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the SAA1099 player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the SAA1099"
        }
        return null
    }

    /** The noise clock [clock] in Hz on [variant] for clocks 0-2 (clock / 256, / 512, / 1024); 0.0 for clock 3. */
    fun noiseHz(clock: Int, variant: SaaVariant): Double = if (clock in 0..2) variant.clock / (256.0 * (1 shl clock)) else 0.0

    /**
     * Noise clock [clock] for messages: "31.3 kHz (clock / 256)", or for clock 3 "twice SAA 1's tone" naming the lane
     * that drives it for a note on [channel] (lane 1 or 4 of its chip; "lane 1's or 4's" without a channel).
     */
    fun noiseText(clock: Int, variant: SaaVariant, channel: Int = -1): String = when (clock) {
        in 0..2 -> "${NumberText.fixed(noiseHz(clock, variant) / 1000.0, 1)} kHz (clock / ${256 shl clock})"
        3 -> if (channel in 0 until variant.channels) "twice ${variant.laneNames[SaaSound.noiseClockLane(channel)]}'s tone"
            else "twice lane 1's or 4's tone"
        else -> "clock $clock"
    }

    /** Noise clock [clock] as a lane tag: "31k", "16k", "7.8k" (SAM; CMS: "28k", "14k", "7.0k"), "T1" / "T4" for clock 3. */
    fun noiseTag(clock: Int, variant: SaaVariant, generator: Int): String = when (clock) {
        in 0..1 -> "${round(noiseHz(clock, variant) / 1000.0).toInt()}k"
        2 -> "${NumberText.fixed(noiseHz(clock, variant) / 1000.0, 1)}k"
        else -> "T${3 * generator + 1}"
    }

    /** "repetitive decay, 4-bit, internal clock (SAA 2)": hardware envelope control [control] in words, for a note on [channel]. */
    fun envelopeText(control: Int, variant: SaaVariant?, channel: Int, rate: Int = 0): String {
        val e = SaaHardwareEnvelope.ofControl(control, maxOf(rate, 1)) ?: return "off"
        val clock = when {
            e.external && rate > 0 -> "external clock, a step every $rate tick${if (rate == 1) "" else "s"}"
            e.external -> "external clock"
            variant != null && channel in 0 until variant.channels && SaaSound.hasEnvelope(channel) ->
                "clocked by ${variant.laneNames[SaaSound.envelopeClockLane(channel)]}'s tone"
            else -> "clocked by lane 2's or 5's tone"
        }
        return SaaHardwareEnvelope.SHAPE_NAMES[e.shape] + ", " + (if (e.threeBit) "3-bit" else "4-bit") +
            (if (e.invertRight) ", right inverted" else "") + ", " + clock
    }

    /** The semitones a repetitive internal-clock envelope [e] sounds below its clock lane's note, or null for other shapes. */
    fun envelopeInterval(e: SaaHardwareEnvelope): Int? {
        if (e.external || !SaaHardwareEnvelope.looping(e.shape) || e.shape == SaaHardwareEnvelope.MAXIMUM) return null
        // Two steps per period of the clock lane's tone: a cycle of n steps is n / 2 periods long.
        val periods = SaaHardwareEnvelope.steps(e.shape, e.threeBit) / 2
        return (12 * log2(periods.toDouble())).roundToInt()
    }

    /** What `Yxx` [param] does. */
    fun syncText(param: Int): String = when (param) {
        SYNC_NORMAL -> "sound on, generators running"
        SYNC_RESTART -> "every tone and noise generator restarts together, in phase"
        SYNC_HOLD -> "every generator held at its start (silent) until Y00 or Y01"
        SYNC_OFF -> "sound off until Y00"
        else -> "not a sync value"
    }

    /** What mixer value [m] plays. */
    fun mixerText(m: Int): String = when (m) {
        0 -> "off"
        1 -> "tone"
        2 -> "noise"
        3 -> "tone + noise"
        else -> "?"
    }

    /** Human-readable meaning of [effect]/[param] on [channel] for the inspector and picker. */
    fun describe(song: Song?, channel: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        val variant = (song?.sound as? SaaSound)?.variant
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_PAN -> "Pan left $x, right $y for this note"
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y sixteenths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> "Volume ${NumberText.hex(param)} of F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_ENVELOPE -> {
                val lanes = if (variant != null && channel in 0 until variant.channels && !SaaSound.hasEnvelope(channel)) {
                    " (ignored here: only lanes 3 and 6 have an envelope)"
                } else ""
                if (param == 0) "Hardware envelope off for this note$lanes"
                else "Hardware envelope: " + envelopeText(param, variant, channel) + lanes
            }
            FX_SPEED -> "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_MIXER -> "Mixer ${mixerText(param)} for this note"
            FX_NOISE -> "Noise clock " + (variant?.let { noiseText(param, it, channel) } ?: "$param") + " for this note"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_SYNC -> "Chip sync: " + syncText(param)
            else -> "Unknown effect"
        }
    }

    /** Cents [note] sounds away from 12-TET A440 on [variant]'s chips: the divider's rounding (within 3.4 cents). */
    fun cents(note: Int, variant: SaaVariant): Double {
        val hz = SaaPlayerImage.hz(SaaPlayerImage.octaveOffset(note * SaaPlayerImage.PITCH_UNITS_PER_SEMITONE, variant.cms), variant.cms)
        return 1200.0 * log2(hz / (440.0 * 2.0.pow((note - 57) / 12.0)))
    }

    /**
     * One-line reading of [ins]: mixer, volume shape, pan, noise clock, hardware envelope and the other envelopes and
     * flags ("tone · vol 15→0, release · pan 15/15 · env repetitive decay").
     */
    fun summary(ins: SaaInstrument, variant: SaaVariant): String {
        val parts = ArrayList<String>()
        val mixer = ins.mixer.values
        parts += when {
            mixer.isEmpty() -> "tone"
            mixer.distinct().size == 1 -> mixerText(mixer[0])
            else -> "mixer " + mixer.take(6).joinToString(" ") + (if (mixer.size > 6) " …" else "") + if (ins.mixer.loop >= 0) " loop" else ""
        }
        val vol = ins.volume
        parts += if (vol.values.isEmpty()) "vol 15" else buildString {
            append("vol ")
            append(if (vol.values.size == 1) "${vol.values[0]}" else "${vol.values.first()}→${vol.values.last()}")
            if (vol.loop >= 0) append(", loop")
            if (vol.release >= 0) append(", release")
        }
        parts += "pan ${ins.panLeft}/${ins.panRight}"
        if (ins.usesNoise) parts += "noise " + if (ins.noise == 3) "T1/T4" else noiseTag(ins.noise, variant, 0)
        ins.envelope?.let { parts += "env " + SaaHardwareEnvelope.SHAPE_NAMES[it.shape] + if (it.external) " ext" else "" }
        if (ins.arpeggio.values.isNotEmpty()) parts += "arp " + ins.arpeggio.values.take(6).joinToString(" ") + if (ins.arpeggio.loop >= 0) " loop" else ""
        if (ins.pitch.values.isNotEmpty()) parts += if (ins.pitchRelative) "pitch sweep" else "pitch env"
        return parts.joinToString(" · ")
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }
}
