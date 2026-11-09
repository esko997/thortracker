package org.thortracker.system.cbm

import org.thortracker.backend.CbmImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec
import kotlin.math.floor
import kotlin.math.pow

/**
 * VIC-20 and C16/Plus/4 cell semantics: Thor Tracker's own player (docs/AUDIO_ENGINE.md, "VIC-20 and C16/Plus/4"),
 * two effect columns per lane, FamiTracker letters where meanings match. The chip has one master volume (`Vxx`,
 * `Axy`) and on the TED one DAC timer (`Txx`): shared effects that act for every lane.
 */
object CbmRules {
    /** Effect columns per lane (the player's limit). */
    const val EFFECT_COLUMNS = CbmImage.MAX_EFFECTS

    val VIC_LANES = listOf("Bass", "Alto", "Soprano", "Noise")
    val TED_LANES = listOf("Voice 1", "Voice 2", "DAC")

    fun laneNames(chip: CbmChip): List<String> = if (chip == CbmChip.VIC) VIC_LANES else TED_LANES

    /** Every note any lane of either chip takes: 0..127 (the VIC noise lane's rate indices, TED voice 2's noise). */
    val NOTES: IntRange = 0..CbmImage.NOISE_RATE_MAX

    // Effect codes: the player's (cbm_image.h).
    const val FX_ARPEGGIO = CbmImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = CbmImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = CbmImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = CbmImage.FX_PORTAMENTO
    const val FX_VIBRATO = CbmImage.FX_VIBRATO
    const val FX_VOLUME_SLIDE = CbmImage.FX_VOLUME_SLIDE
    const val FX_JUMP = CbmImage.FX_JUMP
    const val FX_BREAK = CbmImage.FX_BREAK
    const val FX_SPEED = CbmImage.FX_SPEED
    const val FX_DELAY = CbmImage.FX_DELAY
    const val FX_FINE = CbmImage.FX_FINE
    const val FX_CUT = CbmImage.FX_CUT
    const val FX_TIMER = CbmImage.FX_TIMER
    const val FX_VOLUME = CbmImage.FX_VOLUME
    const val FX_WAVE = CbmImage.FX_WAVE

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = CbmImage.FINE_NONE

    /** Effects the VIC noise lane's player ignores (it plays rates, not pitches): not offered there. */
    val PITCH_EFFECTS = setOf(FX_SLIDE_UP, FX_SLIDE_DOWN, FX_PORTAMENTO, FX_VIBRATO, FX_FINE)

    // ---- lanes ----------------------------------------------------------------------------------------------------

    /** The notes [channel] plays on [chip] in [video]; empty for a channel the chip lacks. */
    fun laneRange(chip: CbmChip, video: CbmVideo, channel: Int): IntRange = CbmImage.laneRange(chip.code, video.code, channel)

    /** True on the VIC's Noise lane (notes are rate indices). */
    fun isVicNoise(chip: CbmChip, channel: Int): Boolean = chip == CbmChip.VIC && channel == CbmImage.VIC_NOISE_CHANNEL

    /** True on the TED's DAC lane. */
    fun isDac(chip: CbmChip, channel: Int): Boolean = chip == CbmChip.TED && channel == CbmImage.TED_DAC_CHANNEL

    /** True on a voice lane (every VIC lane, TED voices 1-2). */
    fun isVoice(chip: CbmChip, channel: Int): Boolean = channel in 0 until chip.channels && !isDac(chip, channel)

    /**
     * Why [ins] may not play on [channel] of [chip] (the loader's placement rules), or null: tone sounds on voice lanes,
     * a tone sound that can play noise only on TED voice 2, sample sounds only on the TED DAC lane.
     */
    fun placementProblem(chip: CbmChip, channel: Int, ins: CbmInstrument): String? = when {
        ins.kind == CbmKind.SAMPLE && chip == CbmChip.VIC -> "is a sample sound; the VIC-20 has no D/A"
        ins.kind == CbmKind.SAMPLE && !isDac(chip, channel) -> "is a sample sound; samples play on the DAC lane only"
        ins.kind == CbmKind.TONE && isDac(chip, channel) -> "is a tone sound; the DAC lane plays sample sounds only"
        chip == CbmChip.TED && ins.playsNoise && channel != CbmImage.TED_NOISE_CHANNEL ->
            "can play noise, which the TED has on voice 2 only; give Voice 1 a square sound"
        else -> null
    }

    // ---- clocks and pitch -----------------------------------------------------------------------------------------

    /** Chip clock (the timeline unit) of [chip] in [video]: VIC φ2, TED single clock, as the exact quotients. */
    fun clockHz(chip: CbmChip, video: CbmVideo): Double = when (chip) {
        CbmChip.VIC -> if (video == CbmVideo.PAL) 17_734_475.0 / 16 else 315_000_000.0 / 308
        CbmChip.TED -> if (video == CbmVideo.PAL) 17_734_475.0 / 20 else 315_000_000.0 / 352
    }

    /** Cycles of one frame: VIC 71 × 312 / 65 × 261, TED 57 × 312 / 57 × 262. */
    fun frameCycles(chip: CbmChip, video: CbmVideo): Int = when (chip) {
        CbmChip.VIC -> if (video == CbmVideo.PAL) 22152 else 16965
        CbmChip.TED -> if (video == CbmVideo.PAL) 17784 else 14934
    }

    /** Frames per second of the machine: VIC 50.0363 / 60.2845 Hz, TED 49.8608 / 59.9228 Hz. */
    fun frameHz(chip: CbmChip, video: CbmVideo): Double = clockHz(chip, video) / frameCycles(chip, video)

    /** The period table's variant index: chip · 2 + video. */
    fun variant(chip: CbmChip, video: CbmVideo): Int = chip.code * 2 + video.code

    /** The table's clock: φ2 (VIC) or the TED's sound clock (single clock / 4). */
    private fun tableClock(chip: CbmChip, video: CbmVideo): Double = when (chip) {
        CbmChip.VIC -> if (video == CbmVideo.PAL) 17_734_475.0 / 16 else 315_000_000.0 / 308
        CbmChip.TED -> if (video == CbmVideo.PAL) 17_734_475.0 / 80 else 315_000_000.0 / 1408
    }

    /** `CBP_PERIOD_Q16[v][i]`: floor(clk / (div · 440 · 2^((i / 64 − 57) / 12)) · 65536 + 0.5), div 64 VIC / 2 TED. */
    fun periodQ16(chip: CbmChip, video: CbmVideo, i: Int): Long {
        val div = if (chip == CbmChip.VIC) 64.0 else 2.0
        return floor(tableClock(chip, video) / (div * 440.0 * 2.0.pow((i / 64.0 - 57) / 12)) * 65536 + 0.5).toLong()
    }

    /** The VIC voice's octave shift: alto 1, bass 2, else 0. */
    fun octaveShift(chip: CbmChip, channel: Int): Int = if (chip == CbmChip.VIC && channel in 0..1) 2 - channel else 0

    /**
     * The period the player writes for [pitch] (1/64 semitone) on [channel]: q = p + 768 s, (T[q mod 768] + 2^(15 + o))
     * >> (16 + o), clamped 1..128 (VIC a) or 2..1024 (TED reload P).
     */
    fun period(chip: CbmChip, video: CbmVideo, channel: Int, pitch: Int): Int {
        val q = pitch.coerceIn(0, PITCH_MAX) + 768 * octaveShift(chip, channel)
        val o = q / 768
        val t = periodQ16(chip, video, q % 768)
        val p = ((t + (1L shl (15 + o))) shr (16 + o)).toInt()
        return if (chip == CbmChip.VIC) p.coerceIn(1, 128) else p.coerceIn(2, 1024)
    }

    /** The highest pitch the player computes (1/64 semitone). */
    const val PITCH_MAX = 8191

    /** The frequency a tone [note] on [channel] sounds at: VIC φ2 / (16 · (a << shift)), TED sound clock / (2 P). */
    fun toneHz(chip: CbmChip, video: CbmVideo, channel: Int, note: Int): Double {
        val p = period(chip, video, channel, note * 64)
        return when (chip) {
            CbmChip.VIC -> clockHz(chip, video) / (16.0 * (p shl (4 - channel.coerceIn(0, 2))))
            CbmChip.TED -> tableClock(chip, video) / (2.0 * p)
        }
    }

    /** Cents [chip] plays tone [note] off true pitch (A-4 = 440 Hz) on [channel]. */
    fun cents(chip: CbmChip, video: CbmVideo, channel: Int, note: Int): Double =
        1200.0 * kotlin.math.ln(toneHz(chip, video, channel, note) / (440.0 * 2.0.pow((note - 57) / 12.0))) / kotlin.math.ln(2.0)

    /** The DAC timer's interrupt period in single clocks: 64 + 4 T. */
    fun timerCycles(timer: Int): Int = 64 + 4 * timer.coerceIn(0, CbmSound.MAX_TIMER)

    /** The DAC rate at timer [timer]: single clock / (64 + 4 T); 7917 Hz PAL at T = 12. */
    fun dacHz(video: CbmVideo, timer: Int): Double = clockHz(CbmChip.TED, video) / timerCycles(timer)

    /** The DAC rate in whole Hz, as people set it: "7917 Hz". */
    fun dacText(video: CbmVideo, timer: Int): String = NumberText.fixed(dacHz(video, timer), 0) + " Hz"

    /** `CBP_STEP_Q16[i]`: floor(65536 · 2^(i / 768) + 0.5). */
    fun stepQ16(i: Int): Long = floor(65536 * 2.0.pow(i / 768.0) + 0.5).toLong()

    /**
     * The sample step (16.16 frames per timer interrupt) for [pitch] (1/64 semitone) on a sample with root note
     * [root]: d = pitch − 64 root, o = floor(d / 768), STEP[d − 768 o] shifted by o, clamped 1..2^24.
     */
    fun sampleStep(pitch: Int, root: Int): Long {
        val d = pitch.coerceIn(0, PITCH_MAX) - 64 * root
        val o = d.floorDiv(768)
        val s = stepQ16(d - 768 * o)
        val step = if (o >= 0) s shl o.coerceAtMost(30) else s shr (-o).coerceAtMost(62)
        return step.coerceIn(1L, 1L shl 24)
    }

    /** A frequency as people read it: "0.42 Hz", "59.1 Hz", "440 Hz", "7.9 kHz". */
    fun hzText(hz: Double): String = when {
        hz >= 1000 -> NumberText.fixed(hz / 1000, 1) + " kHz"
        hz >= 100 -> NumberText.fixed(hz, 0) + " Hz"
        hz >= 10 -> NumberText.fixed(hz, 1) + " Hz"
        else -> NumberText.fixed(hz, 2) + " Hz"
    }

    // ---- effects ------------------------------------------------------------------------------------------------

    private val ARPEGGIO = EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
        "Cycle note, +x, +y every tick for the note on this row (or the sounding note); on the VIC's Noise lane it steps " +
            "the noise rate. The next note without 0xy plays plain.", true)
    private val SLIDE_UP = EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
        "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true)
    private val SLIDE_DOWN = EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
        "Lower the pitch every tick from this row until 100, 200 or 300.", true)
    private val PORTAMENTO = EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
        "While on, a note on a sounding lane glides there without restarting; 300 ends it.", true)
    private val VIBRATO = EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
        "Pitch wobble (depth F: ±1.86 semitones); 4x0 stops it; the phase restarts with each note.", true)
    private val VOLUME_SLIDE = EffectSpec(FX_VOLUME_SLIDE, "Master volume slide", "xy: x up, y down (1/8 level per tick)",
        "The chip's one master volume slides every tick, for every lane, until A00.", true)
    private val JUMP = EffectSpec(FX_JUMP, "Jump", "xx: order",
        "After this row continue at order xx row 0 (with Dxx: its row).", true)
    private val BREAK = EffectSpec(FX_BREAK, "Pattern break", "xx: row",
        "After this row continue at row xx of the next order (of Bxx's order with both).", true)
    private val SPEED = EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
        "Ticks per row from this row on, all lanes; the last Fxx of a row wins.", true)
    private val DELAY = EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
        "Run this lane's row (note, sound and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true)
    private val FINE = EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
        "Detune by xx − 80 sixty-fourths of a semitone until the next note; tunings use it.", true)
    private val CUT = EffectSpec(FX_CUT, "Note off", "xx: tick",
        "OFF at tick xx of this row, like an OFF note at that tick.", true)
    private val TIMER = EffectSpec(FX_TIMER, "DAC rate", "xx: timer 00-FF",
        "The TED timer that plays samples, for every lane: one frame every 64 + 4·xx clocks (T00 13.9 kHz, T0C 7.9 kHz, " +
            "TFF 0.8 kHz on PAL), from the timer's next reload. Sample notes play relative to it.", true)
    private val VIC_VOLUME = EffectSpec(FX_VOLUME, "Master volume", "xx: 00-0F",
        "The chip's one volume for all four voices from this row on (a sound with a volume envelope shapes it while it sounds).", true)
    private val TED_VOLUME = EffectSpec(FX_VOLUME, "Master volume", "xx: 00-08",
        "The chip's one volume for both voices from this row on (a sound with a volume envelope shapes it while it sounds; " +
            "the DAC ignores it).", true)
    private val WAVE = EffectSpec(FX_WAVE, "Wave", "xx: 00 off, 01 square, 02 noise (Voice 2)",
        "The sounding note's wave and later notes' until a note row without W; ignored by sounds with a wave envelope.", true)

    /** Every effect the VIC-20 player knows, in code order. */
    val VIC_EFFECTS: List<EffectSpec> = listOf(ARPEGGIO, SLIDE_UP, SLIDE_DOWN, PORTAMENTO, VIBRATO, VOLUME_SLIDE, JUMP, BREAK,
        SPEED, DELAY, FINE, CUT, VIC_VOLUME)

    /** Every effect the C16/Plus/4 player knows, in code order. */
    val TED_EFFECTS: List<EffectSpec> = listOf(ARPEGGIO, SLIDE_UP, SLIDE_DOWN, PORTAMENTO, VIBRATO, VOLUME_SLIDE, JUMP, BREAK,
        SPEED, DELAY, FINE, CUT, TIMER, TED_VOLUME, WAVE)

    fun effects(chip: CbmChip): List<EffectSpec> = if (chip == CbmChip.VIC) VIC_EFFECTS else TED_EFFECTS

    private val VIC_NOISE_EFFECTS = VIC_EFFECTS.filter { it.code !in PITCH_EFFECTS }
    private val TED_DAC_EFFECTS = TED_EFFECTS.filter { it.code != FX_WAVE }

    /** The effects lane [channel] of [chip] offers: no pitch effects on the VIC Noise lane, no `W` on the DAC lane. */
    fun effectsFor(chip: CbmChip, channel: Int): List<EffectSpec> = when {
        isVicNoise(chip, channel) -> VIC_NOISE_EFFECTS
        isDac(chip, channel) -> TED_DAC_EFFECTS
        else -> effects(chip)
    }

    fun effect(chip: CbmChip, code: Int): EffectSpec? = effects(chip).firstOrNull { it.code == code }

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /**
     * Why effect [code]/[param] on [channel] of [chip] cannot load (the checks `cb_load_image` makes) in a song of
     * [orders] orders, or null: unknown codes, `T`/`W` on the VIC, `W` on the DAC lane, `W02` off voice 2, `W` above
     * 02, `Bxx` past the last order, `F00`, `Vxx` above the chip's master volume.
     */
    fun paramProblem(chip: CbmChip, channel: Int, code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        if (effect(chip, code) == null) {
            return if (chip == CbmChip.VIC && (code == FX_TIMER || code == FX_WAVE)) "$fx: the VIC-20 has no ${if (code == FX_TIMER) "DAC timer" else "wave choice"} (TED only)"
            else "effect ${EffectSpec.charOf(code)} does not exist on the ${chip.system.name}"
        }
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_VOLUME -> if (param > chip.maxVolume) "$fx: the master volume is V00-V${NumberText.hex(chip.maxVolume, 2)}" else null
            FX_WAVE -> when {
                isDac(chip, channel) -> "$fx: the DAC lane plays samples; Wxx is for Voice 1 and Voice 2"
                param > CbmImage.WAVE_NOISE -> "$fx: waves are W00 (off), W01 (square) and W02 (noise)"
                param == CbmImage.WAVE_NOISE && channel != CbmImage.TED_NOISE_CHANNEL -> "$fx: the TED plays noise on Voice 2 only"
                else -> null
            }
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and lane, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) {
            return "note ${cell.note} is outside ${NOTES.first}-${NOTES.last}, the notes the player takes"
        }
        if (cell.instrument !in 0..CbmSystem.SLOTS) return "sound ${cell.instrument} is outside 0-${CbmSystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the player plays at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
        }
        return null
    }

    /** Human-readable meaning of [effect]/[param] on [chip]; [song] gives the DAC rate's machine. */
    fun describe(chip: CbmChip, song: Song?, channel: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        val noise = isVicNoise(chip, channel)
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else if (noise) "Arpeggio: rate, +$x, +$y steps" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_VOLUME_SLIDE -> if (param == 0) "Master volume slide off" else "Master volume slide +$x −$y eighths per tick, every lane"
            FX_JUMP -> "Jump to order $hex"
            FX_BREAK -> "Break to row $param of the next order"
            FX_SPEED -> if (param == 0) "Speed: invalid (01-FF)" else "Speed $param ticks per row"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${if (d > 0) "+$d" else "$d"}/64 semitone"
            }
            FX_CUT -> "Note off at tick $param"
            FX_TIMER -> if (chip != CbmChip.TED) "DAC rate: the VIC-20 has none"
                else "DAC rate ${dacText((song?.sound as? CbmSound)?.video ?: CbmVideo.PAL, param)}, every lane"
            FX_VOLUME -> if (param > chip.maxVolume) "Master volume: invalid (00-${NumberText.hex(chip.maxVolume, 2)})"
                else "Master volume $param of ${chip.maxVolume}, every lane"
            FX_WAVE -> when (param) {
                0 -> "Wave off: the voice is silent"
                1 -> "Wave: square"
                2 -> "Wave: noise (Voice 2)"
                else -> "Wave: invalid (00-02)"
            }
            else -> "Unknown effect"
        }
    }
}
