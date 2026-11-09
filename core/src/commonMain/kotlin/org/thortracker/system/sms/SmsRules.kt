package org.thortracker.system.sms

import org.thortracker.backend.SmsImageLayout
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.EffectSpec

/**
 * Master System cell semantics: the Banjo driver's pattern commands (docs/AUDIO_ENGINE.md, "Master System: effects"),
 * FamiTracker letters where meanings match, up to [EFFECT_COLUMNS] effect columns run in column order. Arpeggio,
 * slides, portamento and vibrato share one slot per channel in Banjo: the last one on a row wins and any of them stops
 * the others. Speed changes take effect from the next row (the driver reloads its row timer before reading a row).
 */
object SmsRules {
    const val EFFECT_COLUMNS = 4

    const val FX_ARPEGGIO = 0
    const val FX_SLIDE_UP = 1
    const val FX_SLIDE_DOWN = 2
    const val FX_PORTAMENTO = 3
    const val FX_VIBRATO = 4
    const val FX_PAN = 8
    const val FX_JUMP = 11 // B
    const val FX_VOLUME = 12 // C
    const val FX_BREAK = 13 // D
    const val FX_SPEED = 15 // F
    const val FX_DELAY = 16 // G
    const val FX_LEGATO = 21 // L
    const val FX_NOISE = 23 // N
    const val FX_ODD_SPEED = 24 // O
    const val FX_CUT = 28 // S

    /** The effects sharing Banjo's one pitch-effect slot. */
    val PITCH_EFFECTS = setOf(FX_ARPEGGIO, FX_SLIDE_UP, FX_SLIDE_DOWN, FX_PORTAMENTO, FX_VIBRATO)

    /** Lowest note the SN76489's tones reach in Banjo (its table starts at A-2, 110 Hz): lower notes play A-2. */
    const val PSG_LOWEST = 33

    /** Lowest Noise note whose fixed rate Banjo looks up in its table (C-3); lower ones pick an arbitrary rate. */
    const val NOISE_LOWEST = 36

    /** Highest playable note: B-8 (the SN76489's periods are coarse up there); FM and rhythm stop at B-7. */
    const val HIGHEST = 107

    private val ALL = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "Cycle +x, +y and the note every tick for the note on this row (or the sounding note); the next note without 0xy plays plain. " +
                "Replaces a slide or vibrato.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: 1/32 semitone per tick, 00 off",
            "Raise the pitch every tick from this row until 100; new notes start where they are written and keep sliding. Replaces an arpeggio or vibrato.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: 1/32 semitone per tick, 00 off",
            "Lower the pitch every tick from this row until 200.", true),
        EffectSpec(FX_PORTAMENTO, "Portamento", "xx: 1/32 semitone per tick, 00 off",
            "The note on this row glides from the sounding pitch; reaching it ends the glide (write 3xx on each note to glide).", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x speed 1-F, y depth 1-F",
            "Wobble the pitch (depth F: ±½ semitone; speed x: 64/x ticks per cycle); 4x0 or 40y stops it; the phase restarts with each note.", true),
        EffectSpec(FX_PAN, "Pan", "xy: x≠0 left, y≠0 right (Game Gear)",
            "This channel on the left (10), right (01) or both (11) Game Gear speakers; 800 silences it. From this row on.", true),
        EffectSpec(FX_JUMP, "Jump", "xx: order",
            "After this row continue at order xx, row 0.", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Channel volume from this row on (start 0F); instruments' volume envelopes scale under it.", true),
        EffectSpec(FX_BREAK, "Pattern break", "00",
            "After this row continue at the next order's row 0 (Banjo always starts an order at row 0).", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-FF",
            "Ticks per row from the next row on, all channels (Banjo reloads the row timer before reading a row).", true),
        EffectSpec(FX_DELAY, "Note delay", "xx: ticks",
            "Run the rest of this channel's row (instrument, note and effects) xx ticks late; must be shorter than the row.", true),
        EffectSpec(FX_LEGATO, "Legato", "01 on, 00 off",
            "While on, new notes change pitch without restarting the envelopes (FM: without a new attack).", true),
        EffectSpec(FX_NOISE, "Noise mode", "xy: x 0 periodic / 1 white, y 0 fixed / 1 pitched (Noise)",
            "Fixed: the note picks the rate (C /2048, C# /1024, D-B /512); pitched: the note sets Square 3's period, which the noise follows. " +
                "The noise channel starts white and pitched (Banjo's default).", true),
        EffectSpec(FX_ODD_SPEED, "Odd-row speed", "xx: ticks 01-FF",
            "Ticks of the odd rows (1, 3, 5…) from the next row on; Fxx sets both. Shuffles and swing.", true),
        EffectSpec(FX_CUT, "Note off", "xx: tick 01-FF",
            "OFF at tick xx of this row (a release envelope plays, FM keys off); must be shorter than the row.", true),
    )

    private val BY_CODE = ALL.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** Every effect the layout names (the Game Gear adds pan). */
    fun effects(layout: SmsLayout): List<EffectSpec> = if (layout.stereo) ALL else ALL.filter { it.code != FX_PAN }

    /** The effects [channel] of [layout] plays. */
    fun effectsFor(layout: SmsLayout, channel: Int): List<EffectSpec> = effects(layout).filter { available(layout, channel, it.code) }

    /** Whether Banjo plays effect [code] on [channel]: rhythm drums take no pitch effects or legato, noise modes only on Noise. */
    fun available(layout: SmsLayout, channel: Int, code: Int): Boolean {
        if (effect(code) == null || (code == FX_PAN && !layout.stereo)) return false
        return when (layout.kind(channel)) {
            SmsChannelKind.DRUM -> code !in PITCH_EFFECTS && code != FX_LEGATO && code != FX_NOISE && code != FX_PAN
            SmsChannelKind.NOISE -> true
            SmsChannelKind.SQUARE -> code != FX_NOISE
            SmsChannelKind.FM -> code != FX_NOISE && code != FX_PAN
        }
    }

    fun effectText(effect: Int, param: Int) = "${EffectSpec.charOf(effect)}${NumberText.hex(param, 2)}"

    /** Why effect [code]/[param] cannot be lowered on [channel], given the order count; or null. */
    fun paramProblem(layout: SmsLayout, channel: Int, code: Int, param: Int, orders: Int): String? {
        val x = param shr 4
        val y = param and 0x0F
        val fx = effectText(code, param)
        if (effect(code) == null || (code == FX_PAN && !layout.stereo)) return "effect ${EffectSpec.charOf(code)} does not exist on the ${layout.displayName}"
        if (!available(layout, channel, code)) return "$fx does not play on ${layout.channelNames[channel]}"
        return when (code) {
            FX_JUMP -> if (param >= orders) "$fx jumps past the last order (${NumberText.hex(orders - 1, 2)})" else null
            FX_VOLUME -> if (param > 0x0F) "$fx: volumes are C00-C0F" else null
            FX_BREAK -> if (param != 0) "$fx: Banjo starts the next order at row 0; write D00" else null
            FX_SPEED, FX_ODD_SPEED -> if (param == 0) "$fx: speeds are 01-FF ticks per row" else null
            FX_LEGATO -> if (param > 1) "$fx: legato is L01 (on) or L00 (off)" else null
            FX_NOISE -> if (x > 1 || y > 1) "$fx: x is 0 (periodic) or 1 (white), y 0 (fixed rate) or 1 (pitched)" else null
            FX_CUT -> if (param == 0) "S00: write OFF for a note off at the row's start" else null
            else -> null
        }
    }

    /** Range problem of a single cell independent of the song and channel, or null. */
    fun cellRangeProblem(layout: SmsLayout, cell: Cell): String? {
        if (cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in 0..HIGHEST) return "note ${cell.note} is outside C-0..B-8 (0-$HIGHEST)"
        if (cell.instrument !in 0..SmsSystem.SLOTS) return "instrument ${cell.instrument} is outside 0-${SmsSystem.SLOTS}"
        if (cell.more.size >= EFFECT_COLUMNS) return "${cell.more.size + 1} effect columns; Banjo songs have at most $EFFECT_COLUMNS"
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code !in 0..EffectSpec.MAX_CODE) return "effect $code is outside 0-Z"
            if (param !in 0..255) return "effect parameter $param is outside 00-FF"
            if ((code != 0 || param != 0) && (effect(code) == null || (code == FX_PAN && !layout.stereo))) {
                return "effect ${EffectSpec.charOf(code)} does not exist on the ${layout.displayName}"
            }
        }
        return null
    }

    /** The note byte Banjo plays absolute [note] with on [channel]: SN channels count from A-2 = 9, the YM2413 from C-0. */
    fun driverNote(layout: SmsLayout, channel: Int, note: Int): Int = when (layout.kind(channel)) {
        SmsChannelKind.SQUARE, SmsChannelKind.NOISE -> (note - SmsImageLayout.SN_NOTE_OFFSET).coerceIn(0, 127)
        else -> note.coerceIn(0, 127)
    }

    /** The noise source the Noise channel's mode [mode] (bit 0 white, bit 1 pitched) and absolute [note] give. */
    fun noiseText(mode: Int, note: Int?): String {
        val kind = if (mode and 1 != 0) "white" else "periodic"
        if (mode and 2 != 0) return "$kind, pitched by Square 3"
        if (note == null) return "$kind, fixed rate (the note picks it)"
        return "$kind ${NOISE_RATES[noiseRate(note)]}"
    }

    /** Banjo's fixed noise rate (0 = clock/512, 1 = /1024, 2 = /2048) for absolute [note]: C /2048, C# /1024, others /512. */
    fun noiseRate(note: Int): Int = when (note % 12) { 0 -> 2; 1 -> 1; else -> 0 }

    val NOISE_RATES = listOf("/512 (high)", "/1024", "/2048 (low)")

    /** Human-readable meaning of [effect]/[param] for the inspector and picker. */
    fun describe(layout: SmsLayout, channel: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0x0F
        val hex = NumberText.hex(param, 2)
        if (channel in 0 until layout.channelCount && effect(effect) != null && !available(layout, channel, effect)) {
            return "${effect(effect)?.name}: does not play on ${layout.channelNames[channel]}"
        }
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: +$x, +$y semitones, note"
            FX_SLIDE_UP -> if (param == 0) "Slide off" else "Slide up $param/32 semitone per tick"
            FX_SLIDE_DOWN -> if (param == 0) "Slide off" else "Slide down $param/32 semitone per tick"
            FX_PORTAMENTO -> if (param == 0) "Slide off" else "Portamento $param/32 semitone per tick"
            FX_VIBRATO -> if (x == 0 || y == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_PAN -> when {
                x != 0 && y != 0 -> "Pan centre (both speakers)"
                x != 0 -> "Pan left"
                y != 0 -> "Pan right"
                else -> "Pan: silent"
            }
            FX_JUMP -> "Jump to order $hex"
            FX_VOLUME -> "Volume $hex of 0F"
            FX_BREAK -> if (param == 0) "Break to the next order" else "Break: Banjo always starts row 0 (write D00)"
            FX_SPEED -> "Speed $param ticks per row from the next row"
            FX_DELAY -> if (param == 0) "No delay" else "Delay this row by $param ticks"
            FX_LEGATO -> when (param) { 0 -> "Legato off"; 1 -> "Legato on"; else -> "Legato: invalid (L00/L01)" }
            FX_NOISE -> if (x > 1 || y > 1) "Noise mode: invalid" else "Noise " + noiseText(x or (y shl 1), null)
            FX_ODD_SPEED -> "Odd rows $param ticks from the next row"
            FX_CUT -> if (param == 0) "Note off: write OFF" else "Note off at tick $param"
            else -> "Unknown effect"
        }
    }

    /**
     * The editor's longer reading where the row matters: the notes an arpeggio steps through from the row's (or the
     * sounding) note, or null.
     */
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        val pitchColumns = (0..cell.more.size).filter { cell.effectCode(it) in PITCH_EFFECTS && (cell.effectCode(it) != 0 || cell.effectParam(it) != 0) }
        if (column in pitchColumns && column != pitchColumns.last()) {
            return "${describe(song.sms.layout, channel, code, param)}: replaced by a later pitch effect on this row (Banjo keeps one)"
        }
        if (code != FX_ARPEGGIO || param == 0) return null
        val x = param shr 4
        val y = param and 0x0F
        var root = if (cell.note >= 0) cell.note else -1
        if (root < 0) {
            for (r in row - 1 downTo 0) {
                val n = track[r].note
                if (n >= 0) { root = n; break }
                if (n == Cell.CUT) break
            }
        }
        if (root < 0) return "+$x, +$y, note: no note sounding here"
        val system = song.system
        return listOf(root + x, root + y, root).joinToString(" → ") { if (it <= HIGHEST) system.noteName(it) else "(out of range)" } + " every tick"
    }

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"
}
