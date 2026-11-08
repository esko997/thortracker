package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.system.EffectSpec

/**
 * Virtual Boy cell semantics: Thor Tracker's own player (docs/AUDIO_ENGINE.md, "Virtual Boy VSU: Effects"), two effect
 * columns on every lane, FamiTracker letters where meanings match the OPL3's. Locks (`C`, `L`, `M`, `N`, `W`) apply to
 * the note keyed on their row; a row that starts no note (no note, OFF, a portamento glide, an empty instrument) writes
 * them to the sounding channel at once (`L` then does nothing). Stereo, slides and vibrato act from their row on.
 */
object VsuRules {
    /** Absolute notes the player takes on the noise lane: C-0..B-7 (0 = C-0, 57 = A-4 = 440 Hz). */
    val NOTES = 0 until VsuPlayerImage.NOTES

    /** Notes the wave lanes play: D#2..B-7 (D-2 would need a negative frequency register). */
    val WAVE_NOTES = VsuPlayerImage.WAVE_NOTE_LOW until VsuPlayerImage.NOTES

    const val EFFECT_COLUMNS = VsuPlayerImage.MAX_EFFECTS
    const val SWEEP_LANE = VsuPlayerImage.SWEEP_LANE
    const val NOISE_LANE = VsuPlayerImage.NOISE_LANE

    val CHANNEL_NAMES = listOf("Wave 1", "Wave 2", "Wave 3", "Wave 4", "Wave 5", "Noise")

    const val FX_ARPEGGIO = VsuPlayerImage.FX_ARPEGGIO
    const val FX_SLIDE_UP = VsuPlayerImage.FX_SLIDE_UP
    const val FX_SLIDE_DOWN = VsuPlayerImage.FX_SLIDE_DOWN
    const val FX_PORTAMENTO = VsuPlayerImage.FX_PORTAMENTO
    const val FX_VIBRATO = VsuPlayerImage.FX_VIBRATO
    const val FX_STEREO = VsuPlayerImage.FX_STEREO
    const val FX_JUMP = VsuPlayerImage.FX_JUMP
    const val FX_LEVEL = VsuPlayerImage.FX_LEVEL
    const val FX_BREAK = VsuPlayerImage.FX_BREAK
    const val FX_SPEED = VsuPlayerImage.FX_SPEED
    const val FX_DELAY = VsuPlayerImage.FX_DELAY
    const val FX_LENGTH = VsuPlayerImage.FX_LENGTH
    const val FX_SWEEP_MOD = VsuPlayerImage.FX_SWEEP_MOD
    const val FX_NOISE_TAP = VsuPlayerImage.FX_NOISE_TAP
    const val FX_FINE = VsuPlayerImage.FX_FINE
    const val FX_CUT = VsuPlayerImage.FX_CUT
    const val FX_WAVE = VsuPlayerImage.FX_WAVE

    /** `Pxx` with no detune. */
    const val FINE_CENTRE = 0x80

    /** Parameter locks: they shape the note keyed on their row, or the sounding channel on a row that starts no note. */
    val LOCKS = setOf(FX_LEVEL, FX_LENGTH, FX_SWEEP_MOD, FX_NOISE_TAP, FX_WAVE)

    fun noteRange(lane: Int): IntRange = if (lane == NOISE_LANE) NOTES else WAVE_NOTES

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle note, +x, +y every tick for the note on this row (or the sounding note); the next note without 0xy plays plain.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/64 semitone per tick",
            "Raise the pitch every tick from this row until 100, 200 or 300; a new note restarts the offset but keeps sliding.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/64 semitone per tick",
            "Lower the pitch every tick from this row until 100, 200 or 300.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/64 semitone per tick, 00 = off",
            "While on, a note on a sounding lane glides there without a new attack (its C/M/N/W locks change the sounding channel at once); 300 ends it.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 0-F, y depth 0-F",
            "Wobble the pitch (depth F: ±1.9 semitones); 4x0 stops it; the phase restarts with each note.", true),
        EffectSpec(FX_STEREO, "Stereo", "xy: x left 0-F, y right 0-F",
            "The lane's left and right levels from this row on (start FF), at once; 800 silences the lane.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx row 0 (with Dxx: its row).", true),
        EffectSpec(FX_LEVEL, "Level lock", "xx: 00-0F",
            "Envelope start level for the note on this row (the sound's direction and interval stay); on a row that " +
                "starts no note the sounding channel takes it at once.", true),
        EffectSpec(FX_BREAK, "Pattern break", "xx: row",
            "After this row continue at row xx of the next order (of Bxx's order with both).", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row (50 per second) from this row on, all lanes; the last Fxx of a row wins.", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run this lane's row (note, instrument and effects except B, D, F) at tick xx; never if xx ≥ the speed.", true),
        EffectSpec(FX_LENGTH, "Length lock", "xx: 00-20",
            "For the note on this row: L00 plays until OFF, L01-L20 stop it after xx × 3.84 ms (the hardware's auto " +
                "length). On a row that starts no note (none, OFF, a glide) it does nothing.", true),
        EffectSpec(FX_SWEEP_MOD, "Sweep/mod lock", "xx: S5SWP (Wave 5 only)",
            "Wave 5: the sweep/modulation byte for the note on this row: bit 7 slow clock (7.68 ms), bits 4-6 interval " +
                "(0 = off), bit 3 sweep up, bits 0-2 shift; the sound's function (sweep or modulation) stays.", true),
        EffectSpec(FX_NOISE_TAP, "Noise tap lock", "xx: 00-07 (Noise only)",
            "Noise: the shift-register tap for the note on this row (sequences of 32767, 1953, 254, 217, 73, 63, 42, " +
                "28 steps); writing it restarts the register.", true),
        EffectSpec(FX_FINE, "Fine pitch", "xx: 80 = none, 1/64 semitone",
            "Detune by xx − 80 sixty-fourths of a semitone (±2 semitones) until the next note; tunings use it.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick",
            "Stop the channel at tick xx of this row (the VSU has no release), like an OFF at that tick.", true),
        EffectSpec(FX_WAVE, "Wave lock", "xx: 01-05 (wave lanes)",
            "Which of the song's five waves the note on this row plays; on a row that starts no note the sounding " +
                "channel switches at once.", true),
    )

    private val BY_CODE = EFFECTS.associateBy { it.code }

    private val WAVE_LANE_EFFECTS = EFFECTS.filter { it.code != FX_SWEEP_MOD && it.code != FX_NOISE_TAP }
    private val SWEEP_LANE_EFFECTS = EFFECTS.filter { it.code != FX_NOISE_TAP }
    private val NOISE_LANE_EFFECTS = EFFECTS.filter { it.code != FX_SWEEP_MOD && it.code != FX_WAVE }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** The effects [lane] declares: `M` on Wave 5 only, `N` on Noise only, `W` on the wave lanes. */
    fun effectsFor(lane: Int): List<EffectSpec> = when (lane) {
        SWEEP_LANE -> SWEEP_LANE_EFFECTS
        NOISE_LANE -> NOISE_LANE_EFFECTS
        else -> WAVE_LANE_EFFECTS
    }

    fun declares(lane: Int, code: Int): Boolean = effectsFor(lane).any { it.code == code }

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot load (the checks `vb_load_image` makes), given the image's order count; or null. */
    fun paramProblem(code: Int, param: Int, orders: Int): String? {
        val fx = effectText(code, param)
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_LEVEL -> if (param > VsuPlayerImage.LEVEL_MAX) "$fx: levels are C00-C0F" else null
            FX_SPEED -> if (param == 0) "F00: speeds are F01-FFF ticks per row" else null
            FX_LENGTH -> if (param > VsuPlayerImage.LENGTH_LOCK_MAX) "$fx: lengths are L00 (until OFF) to L20" else null
            FX_NOISE_TAP -> if (param > VsuPlayerImage.TAP_MAX) "$fx: noise taps are N00-N07" else null
            FX_WAVE -> if (param !in 1..VsuPlayerImage.WAVES) "$fx: waves are W01-W05" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the lane and song, or null. */
    fun cellRangeProblem(cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NOTES) return "note ${cell.note} is outside C-0..B-7 (0-95)"
        if (cell.instrument !in 0..VsuSystem.SLOTS) return "instrument ${cell.instrument} is outside 0-${VsuSystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; the VSU player reads at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && effect(code) == null) return "effect ${EffectSpec.charOf(code)} does not exist on the Virtual Boy"
        }
        return null
    }

    /** What [cell] needs that [lane] does not offer (its note range, its effects), or null. */
    fun laneProblem(lane: Int, cell: Cell): String? {
        if (cell.note >= 0 && cell.note !in noteRange(lane)) {
            return "${VsuSystem.noteName(cell.note)} is below D#2; wave lanes play D#2-B-7 (the Noise lane plays every note)"
        }
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            if ((code == 0 && cell.effectParam(c) == 0) || declares(lane, code)) continue
            return when (code) {
                FX_SWEEP_MOD -> "Mxx (sweep/modulation) exists on Wave 5 only"
                FX_NOISE_TAP -> "Nxx (noise tap) exists on the Noise lane only"
                FX_WAVE -> "Wxx (wave) exists on the wave lanes only; Noise plays its shift register"
                else -> "effect ${EffectSpec.charOf(code)} does not exist on ${CHANNEL_NAMES.getOrElse(lane) { "this lane" }}"
            }
        }
        return null
    }

    /** Human-readable meaning of [effect]/[param] on [lane] for the inspector and picker. */
    fun describe(lane: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/64 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/64 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Portamento off" else "Portamento $param/64 semitone per tick"
            FX_VIBRATO -> if (y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_STEREO -> if (param == 0) "Stereo: silent (both levels 0)" else "Stereo: left $x, right $y of 15"
            FX_JUMP -> "Jump to order $hex"
            FX_LEVEL -> "Envelope level $param of 15 for this note"
            FX_BREAK -> "Break to row $param of the next order"
            FX_SPEED -> "Speed $param ticks per row (${NumberText.fixed(param * 20.0, 0)} ms)"
            FX_DELAY -> "Delay this row by $param ticks"
            FX_LENGTH -> if (param == 0) "Length: plays until OFF" else "Length: stops after ${NumberText.fixed(param * VsuPlayerImage.LENGTH_UNIT_MS, 2)} ms"
            FX_SWEEP_MOD -> "Sweep/mod: ${swpText(param)}" + if (lane >= 0 && lane != SWEEP_LANE) " (Wave 5 only)" else ""
            FX_NOISE_TAP -> "Noise tap $param: ${VsuPitch.tapLength(param)}-step sequence" + if (lane >= 0 && lane != NOISE_LANE) " (Noise only)" else ""
            FX_FINE -> {
                val d = param - FINE_CENTRE
                if (d == 0) "Fine pitch: none" else "Fine pitch ${signed(d)}/64 semitone (${VsuPitch.centsText(d * 100.0 / 64)})"
            }
            FX_CUT -> "Note off at tick $param"
            FX_WAVE -> "Wave $param for this note" + if (lane == NOISE_LANE) " (wave lanes only)" else ""
            else -> "Unknown effect"
        }
    }

    /** S5SWP [swp] in words: "interval 3 × 0.96 ms, up, shift 2"; "off" for interval 0. */
    fun swpText(swp: Int): String {
        val interval = (swp shr VsuPlayerImage.SWP_INTERVAL_SHIFT) and 7
        val unit = if (swp and VsuPlayerImage.SWP_SLOW != 0) VsuPlayerImage.SWEEP_SLOW_MS else VsuPlayerImage.EFFECTS_TICK_MS
        val dir = if (swp and VsuPlayerImage.SWP_UP != 0) "up" else "down"
        val shift = swp and VsuPlayerImage.SWP_SHIFT_MASK
        val time = if (interval == 0) "interval 0 (off)" else "every ${NumberText.fixed(interval * unit, 2)} ms"
        return "$time, sweep $dir, shift $shift"
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"
}
