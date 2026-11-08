package org.thortracker.system.scc

import org.thortracker.backend.SccPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec
import org.thortracker.system.nes.NesEnvelope
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

/**
 * SCC and SCC+ cell semantics: Thor Tracker's own player on a Konami K051649 / K052539 (docs/AUDIO_ENGINE.md, "SCC and
 * SCC+: Effects"), up to [EFFECT_COLUMNS] effect columns per channel, FamiTracker letters where meanings match. `0xy`,
 * `Pxx` and `Wxx` shape the note keyed on their row (or, on a row without a note, the sounding note); volume, slides,
 * vibrato and the test register (`Txx`) act from their row on.
 */
object SccRules {
    /** Notes the system offers: A-0..B-7. The player takes C-0..B-7, but below A-0 the 12-bit period clamps at 4095. */
    val NOTES: IntRange = 9 until SccPlayerImage.NOTES

    /** Effect columns per channel (the player's limit). */
    const val EFFECT_COLUMNS = SccPlayerImage.MAX_EFFECTS

    val CHANNEL_NAMES = (1..SccPlayerImage.CHANNELS).map { "SCC $it" }

    // Effect codes: the player's (generated from scc_image.h).
    const val FX_ARPEGGIO = SccPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = SccPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = SccPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = SccPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = SccPlayerImage.FX_VIBRATO
    const val FX_VOLUME_SLIDE = SccPlayerImage.FX_VOLUME_SLIDE
    const val FX_JUMP = SccPlayerImage.FX_JUMP
    const val FX_VOLUME = SccPlayerImage.FX_VOLUME
    const val FX_BREAK = SccPlayerImage.FX_BREAK
    const val FX_SPEED = SccPlayerImage.FX_SPEED
    const val FX_DELAY = SccPlayerImage.FX_DELAY
    const val FX_FINE = SccPlayerImage.FX_FINE
    const val FX_CUT = SccPlayerImage.FX_CUT
    const val FX_TEST = SccPlayerImage.FX_TEST
    const val FX_WAVE = SccPlayerImage.FX_WAVE

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** The highest note within 12 cents of 12-TET on the 12-bit divider: G#6 (above it, up to +19.2 cents at B-7). */
    const val ACCURATE_TOP = 80

    // Test register bits (`Txx`).
    const val TEST_FREQ_4BIT = 0x01
    const val TEST_FREQ_8BIT = 0x02
    const val TEST_UNUSED = 0x1C
    const val TEST_RESTART = 0x20
    const val TEST_FREEZE_ALL = 0x40
    const val TEST_FREEZE_SHARED = 0x80

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
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: x up, y down (1/16 step per tick)",
            "Change the channel volume every tick until A00 (A01: full to silent in 4 s).", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Channel volume from this row on (start 0F); it scales the instrument's volume envelope.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from this row on, all channels; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this channel's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Key off at tick xx of this row (a release plays if the instrument has one), like an OFF at that tick.", true),
        EffectSpec(FX_TEST, "Test register", "xx: bits",
            "The chip's test register from this row on, every channel: 20 waves restart at each period write, 40 every " +
                "wave frozen, 80 the SCC's shared wave frozen, 00 normal; bits 0-1 are unverified frequency modes.", true),
        EffectSpec(FX_WAVE, "Wave", "xx: wave 01-40",
            "The note on this row plays wave xx instead of its instrument's wave envelope; on a row without a note the " +
                "sounding note switches to wave xx until its next note.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load (the checks `sc_load_image` makes), given the song's order count; or null. */
    fun paramProblem(code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > 0x0F) "$fx: volumes are C00-C0F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_WAVE -> if (param !in 1..SccSound.WAVES) "$fx: waves are W01-W${NumberText.hex(SccSound.WAVES, 2)}" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) {
            return "note ${cell.note} is outside A-0..B-7 (${NOTES.first}-${NOTES.last}), the notes the SCC's 12-bit period reaches"
        }
        if (cell.instrument !in 0..SccSound.SLOTS) return "instrument ${cell.instrument} is outside 0-${SccSound.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the SCC player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the SCC"
        }
        return null
    }

    /** "05 Pulse 25%": wave [number] with its name in [song] (just the number without a name or song). */
    fun waveLabel(song: Song?, number: Int): String {
        val name = (song?.sound as? SccSound)?.wave(number)?.name.orEmpty()
        return if (name.isEmpty()) "wave ${NumberText.hex(number, 2)}" else "wave ${NumberText.hex(number, 2)} \"$name\""
    }

    /**
     * What test register value [value] does, bit by bit, on [variant] (null: say what bit 7 does on each). Empty for 0.
     */
    fun testMeanings(value: Int, variant: SccVariant?): List<String> = buildList {
        if (value and TEST_RESTART != 0) add("waveforms restart at sample 0 at every period write")
        if (value and TEST_FREEZE_ALL != 0) add("every wave RAM is frozen (wave changes are not written)")
        if (value and TEST_FREEZE_SHARED != 0) add(when (variant) {
            SccVariant.SCC -> "the shared wave RAM of SCC 4 and SCC 5 is frozen"
            SccVariant.PLUS -> "bit 7 does nothing on the SCC+ (it freezes the SCC's shared wave)"
            null -> "the SCC's shared wave RAM is frozen (ignored on the SCC+)"
        })
        if (value and TEST_FREQ_8BIT != 0) add("8-bit frequency mode: only the period's low 8 bits count (unverified; VGM players ignore it)")
        else if (value and TEST_FREQ_4BIT != 0) add("4-bit frequency mode: the period's top 4 bits count (unverified; VGM players ignore it)")
        if (value and TEST_UNUSED != 0) add("bits 2-4 have no known function")
    }

    /** Human-readable meaning of [effect]/[param] on [channel] for the inspector and picker. */
    fun describe(song: Song?, channel: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        val variant = (song?.sound as? SccSound)?.variant
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_VOLUME_SLIDE -> if (param == 0) "Volume slide off" else "Volume slide +$x −$y sixteenths per tick"
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> "Volume ${NumberText.hex(param)} of F"
            FX_BREAK -> "Break to row $param of the next order"
            FX_SPEED -> "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${signedCents(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_TEST -> if (param == 0) "Test register 00: normal operation"
                else "Test register $hex: " + testMeanings(param, variant).joinToString("; ")
            FX_WAVE -> {
                val shared = variant == SccVariant.SCC && channel in SccSound.SHARED_CHANNELS
                waveLabel(song, param).replaceFirstChar { it.uppercase() } + " for this note" +
                    if (shared) " (SCC 4 and SCC 5 both play it: one wave RAM)" else ""
            }
            else -> "Unknown effect"
        }
    }

    /** Cents [note] sounds away from 12-TET A440 on the chip: the 12-bit divider's rounding. */
    fun cents(note: Int): Double {
        val hz = SccPlayerImage.hz(SccPlayerImage.period(note * SccPlayerImage.PITCH_UNITS_PER_SEMITONE))
        return 1200.0 * log2(hz / (440.0 * 2.0.pow((note - 57) / 12.0)))
    }

    /**
     * One-line reading of [ins] in [sound]: its wave (or wave sequence), the volume envelope's shape and the other
     * envelopes and flags it uses ("Wave 05 Pulse 25% · vol 15→12, release · arp 0 0 12 12 loop · restart").
     */
    fun summary(ins: SccInstrument, sound: SccSound): String {
        val parts = ArrayList<String>()
        val waves = ins.wave.values
        parts += when {
            waves.isEmpty() -> "no wave"
            waves.distinct().size == 1 -> waveText(sound, waves[0])
            else -> "Waves " + waves.take(8).joinToString(" ") { NumberText.hex(it, 2) } + (if (waves.size > 8) " …" else "") +
                if (ins.wave.loop >= 0) " loop" else ""
        }
        val vol = ins.volume
        parts += if (vol.values.isEmpty()) "vol 15" else buildString {
            append("vol ")
            append(if (vol.values.size == 1) "${vol.values[0]}" else "${vol.values.first()}→${vol.values.last()}")
            if (vol.loop >= 0) append(", loop")
            if (vol.release >= 0) append(", release")
        }
        if (ins.arpeggio.values.isNotEmpty()) parts += "arp " + ins.arpeggio.values.take(6).joinToString(" ") + if (ins.arpeggio.loop >= 0) " loop" else ""
        if (ins.pitch.values.isNotEmpty()) parts += if (ins.pitchRelative) "pitch sweep" else "pitch env"
        if (ins.restart) parts += "restart"
        return parts.joinToString(" · ")
    }

    private fun waveText(sound: SccSound, number: Int): String {
        val name = sound.wave(number)?.name.orEmpty()
        return "Wave ${NumberText.hex(number, 2)}" + if (name.isEmpty()) "" else " $name"
    }

    /** The envelope [values] of [env] as the player steps them, for messages: "05 06 07". */
    fun waveList(env: NesEnvelope): String = env.values.distinct().joinToString(", ") { NumberText.hex(it, 2) }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun signedCents(c: Double): String {
        val r = round(c * 10) / 10
        return (if (r > 0) "+" else "") + r.toString() + "¢"
    }
}
