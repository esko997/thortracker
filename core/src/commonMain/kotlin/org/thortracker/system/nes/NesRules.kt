package org.thortracker.system.nes

import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.ChannelKind
import org.thortracker.system.EffectSpec

/**
 * NES/FamiStudio cell semantics: the effect set, what each effect means on each channel and which values the
 * FamiStudio sound engine 4.5.3 (FamiTracker tempo) can play. Effect codes are the base-36 value of their one-character
 * name (0-9, then A-Z): `C` = 12, `P` = 25. Each effect is a FamiStudio note attribute, so the lowering
 * ([org.thortracker.backend.FamiStudioLowering]) and the FamiStudio text export say the same thing.
 */
object NesRules {
    /** FamiStudio note byte n (1 = C0 in FamiStudio's names) is absolute note n + 11, so 1..96 = C-1..B-8. */
    const val NOTE_OFFSET = 11
    const val NOTE_COUNT = 96

    /**
     * The lowest note the channels with an 11-bit period can play (the 2A03's pulses and triangle, the MMC5's pulses):
     * FamiStudio note 10 (A-1), the longest period in its note table (the triangle sounds it an octave lower). The
     * VRC6's 12-bit periods reach every note.
     */
    const val LOWEST_TONAL = 10 + NOTE_OFFSET

    /** The 2A03's channels in FamiStudio's engine order; an expansion's follow ([NesExpansion.channelNames]). */
    val APU_NAMES = listOf("Pulse 1", "Pulse 2", "Triangle", "Noise", "DPCM")
    private val APU_KINDS = listOf(ChannelKind.PULSE, ChannelKind.PULSE, ChannelKind.TRIANGLE, ChannelKind.NOISE, ChannelKind.SAMPLE)
    const val TRIANGLE = 2
    const val NOISE = 3

    /** The delta modulation channel: notes start the sample their instrument maps to them. */
    const val DPCM = 4

    /** The 2A03's channels; expansion channels follow. */
    const val APU_CHANNELS = 5
    const val VRC6_PULSE1 = 5
    const val VRC6_PULSE2 = 6
    const val VRC6_SAW = 7

    /** The MMC5's pulses (MMC5 Pulse 1 and 2 are channels 5 and 6). */
    const val MMC5_PULSE1 = 5
    const val MMC5_PULSE2 = 6

    /** Channel [ch]'s name in a song with [expansion]. */
    fun channelName(expansion: NesExpansion, ch: Int): String =
        if (ch < APU_CHANNELS) APU_NAMES[ch] else expansion.channelNames.getOrElse(ch - APU_CHANNELS) { "Channel $ch" }

    /** Channel [ch]'s hardware kind in a song with [expansion]. */
    fun kind(expansion: NesExpansion, ch: Int): ChannelKind =
        if (ch < APU_CHANNELS) APU_KINDS[ch] else expansion.channelKinds[ch - APU_CHANNELS]

    /** True for the VRC6 saw of a VRC6 song. */
    fun isVrc6Saw(expansion: NesExpansion, ch: Int): Boolean = expansion == NesExpansion.VRC6 && ch == VRC6_SAW

    /** True for the two pulses of an MMC5 song. */
    fun isMmc5(expansion: NesExpansion, ch: Int): Boolean = expansion == NesExpansion.MMC5 && ch >= MMC5_PULSE1

    /** Most orders: the player indexes orders with a byte (FamiStudio itself allows 256). */
    const val MAX_ORDERS = 255

    /** FamiTracker-tempo speeds FamiStudio accepts (ticks per row). */
    val SPEEDS = 1..31

    const val FX_ARPEGGIO = 0
    const val FX_VIBRATO = 4
    const val FX_VOLUME_SLIDE = 10 // A
    const val FX_VOLUME = 12 // C
    const val FX_SPEED = 15 // F
    const val FX_NOTE_DELAY = 16 // G
    const val FX_FINE_PITCH = 25 // P
    const val FX_SLIDE_UP = 26 // Q
    const val FX_SLIDE_DOWN = 27 // R
    const val FX_NOTE_CUT = 28 // S
    const val FX_DUTY = 31 // V
    const val FX_DELTA_COUNTER = 35 // Z

    /** Zxx: DMC output levels 00-7F. */
    const val DELTA_COUNTER_MAX = 0x7F

    /** Pxx with xx = [FINE_CENTRE] plays the plain note; each step above raises the pitch by one period unit. */
    const val FINE_CENTRE = 0x80

    /** FamiStudio vibrato speeds 0-C and depths 0-F. */
    const val VIBRATO_SPEED_MAX = 12

    /** Longest note delay / cut FamiStudio stores (frames). */
    const val DELAY_MAX = 31

    val EFFECTS = listOf(
        EffectSpec(FX_ARPEGGIO, "Arpeggio", "xy: +x, +y semitones",
            "This row's note cycles every frame through the note, +x and +y semitones. The next note without 0xy plays " +
                "plain. Needs a note on the row. Not on Noise.", true),
        EffectSpec(FX_VIBRATO, "Vibrato", "xy: x=speed 1-C, y=depth 1-F",
            "Wobble the pitch from this row on, across later notes, until 400 (FamiStudio vibrato; it replaces the " +
                "instrument's pitch envelope meanwhile). Not on Noise.", true),
        EffectSpec(FX_VOLUME_SLIDE, "Volume slide", "xy: from volume x to y",
            "Set volume x, then slide to volume y by the next note or effect row on this channel. Not on Triangle.", true),
        EffectSpec(FX_VOLUME, "Volume", "xx: 00-0F",
            "Channel volume from this row on, multiplied with the instrument's volume envelope (0F = as the envelope). " +
                "Not on Triangle.", true),
        EffectSpec(FX_SPEED, "Speed", "xx: ticks per row 01-1F",
            "Change the number of frames per row from this row on. Global.", true),
        EffectSpec(FX_NOTE_DELAY, "Note delay", "xx: frames 01-1F",
            "Start this row's note xx frames late. Must be shorter than the row.", true),
        EffectSpec(FX_FINE_PITCH, "Fine pitch", "xx: 80 = none, 81-FF up, 01-7F down",
            "Detune the playing note by xx-80 period units (above 80 raises the pitch) until the next note; tuned notes " +
                "use it. Not on Noise.", true),
        EffectSpec(FX_SLIDE_UP, "Slide up", "xx: semitones 01-5F",
            "This row's note glides up xx semitones, arriving when the next note (or OFF) on this channel starts.", true),
        EffectSpec(FX_SLIDE_DOWN, "Slide down", "xx: semitones 01-5F",
            "This row's note glides down xx semitones, arriving when the next note (or OFF) on this channel starts.", true),
        EffectSpec(FX_NOTE_CUT, "Note cut", "xx: frames 00-1F",
            "Silence the channel xx frames into this row (S00 = at once). Must be shorter than the row.", true),
        EffectSpec(FX_DUTY, "Duty", "xx: 00-03 (Noise 00-01)",
            "Pulse duty 12.5/25/50/75% from this row on (an instrument duty envelope takes over at its next step); on " +
                "Noise 00 = hiss, 01 = metallic. Not on Triangle.", true),
        EffectSpec(FX_DELTA_COUNTER, "DMC level", "xx: output level 00-7F",
            "DPCM only. Set the DMC output level (FamiStudio's delta counter): with a note, its sample starts from xx " +
                "instead of its initial value; without a note, at once. A high level makes Triangle and Noise quieter.", true),
    )

    /** The duty effect on the VRC6 pulses: eight duties (FamiStudio's `Channel.SupportsEffect`; not on the saw). */
    private val VRC6_DUTY = EffectSpec(FX_DUTY, "Duty", "xx: 00-07 (1/16-8/16)",
        "VRC6 pulse duty from this row on: 00 = 1/16 (6.25%) up to 07 = 8/16 (50%) (an instrument duty envelope takes over " +
            "at its next step). Not on the saw.", true)

    /**
     * The duty effect on the MMC5 pulses: the 2A03's four duties. FamiStudio's engine hides the 2A03 pulses' phase reset
     * with their sweep unit (`FAMISTUDIO_CFG_SMOOTH_VIBRATO`); the MMC5 has no sweep, so its waveform restarts audibly
     * whenever the period's high byte changes.
     */
    private val MMC5_DUTY = EffectSpec(FX_DUTY, "Duty", "xx: 00-03",
        "MMC5 pulse duty 12.5/25/50/75% from this row on (an instrument duty envelope takes over at its next step). The " +
            "MMC5 has no sweep unit, so FamiStudio cannot smooth its pitch changes: when a note, slide or vibrato moves " +
            "the period across a high-byte boundary the waveform restarts with a click (a phase reset).", true)

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** The chip whose instruments [channel] plays in a song with [expansion]. */
    fun chip(expansion: NesExpansion, channel: Int): NesChip = if (channel < APU_CHANNELS) NesChip.APU else expansion.chip

    /** True for the channels whose notes are note-table pitches (pulses, triangle and the VRC6 saw). */
    fun pitched(expansion: NesExpansion, channel: Int): Boolean = kind(expansion, channel).let {
        it == ChannelKind.PULSE || it == ChannelKind.TRIANGLE || it == ChannelKind.SAW
    }

    /**
     * The lowest note [channel] plays: A-1 on the channels with an 11-bit period (the 2A03's pulses and triangle, the
     * MMC5's pulses), every note elsewhere.
     */
    fun lowestNote(expansion: NesExpansion, channel: Int): Int =
        if (pitched(expansion, channel) && (channel < APU_CHANNELS || isMmc5(expansion, channel))) LOWEST_TONAL else 1 + NOTE_OFFSET

    /** The highest duty the duty effect (`Vxx`) takes on [channel]: the VRC6 pulses have eight. */
    fun dutyMax(expansion: NesExpansion, channel: Int): Int =
        if (chip(expansion, channel) == NesChip.VRC6) NesChip.VRC6.duty.last else 3

    /**
     * Effects with no meaning on [channel]: pitch effects on Noise, volume and duty on the Triangle, duty on the VRC6
     * saw, everything but speed, delay, cut and the DMC level on DPCM (FamiStudio's `Channel.SupportsEffect`), and the
     * DMC level elsewhere. The MMC5 pulses take every 2A03 pulse effect.
     */
    private fun unusable(expansion: NesExpansion, channel: Int): Set<Int> = when (kind(expansion, channel)) {
        ChannelKind.NOISE -> setOf(FX_ARPEGGIO, FX_VIBRATO, FX_FINE_PITCH, FX_DELTA_COUNTER)
        ChannelKind.TRIANGLE -> setOf(FX_VOLUME, FX_VOLUME_SLIDE, FX_DUTY, FX_DELTA_COUNTER)
        ChannelKind.SAMPLE -> EFFECTS.map { it.code }.toSet() - setOf(FX_SPEED, FX_NOTE_DELAY, FX_NOTE_CUT, FX_DELTA_COUNTER)
        ChannelKind.SAW -> setOf(FX_DUTY, FX_DELTA_COUNTER)
        else -> setOf(FX_DELTA_COUNTER)
    }

    fun effectsFor(expansion: NesExpansion, channel: Int): List<EffectSpec> = unusable(expansion, channel).let { no ->
        EFFECTS.filter { it.code !in no }.map {
            when {
                it.code != FX_DUTY -> it
                chip(expansion, channel) == NesChip.VRC6 -> VRC6_DUTY
                isMmc5(expansion, channel) -> MMC5_DUTY
                else -> it
            }
        }
    }

    fun hex2(value: Int) = NumberText.hex(value, 2)

    /** The one-character effect name of [code] (0-9, A-Z). */
    fun effectChar(code: Int): Char = if (code < 10) '0' + code else 'A' + (code - 10)

    fun effectText(effect: Int, param: Int) = "${effectChar(effect)}${hex2(param)}"

    /** FamiStudio's note byte for absolute [note]. */
    fun fsNote(note: Int): Int = note - NOTE_OFFSET

    /** Range problem of a single cell independent of context, or null. */
    fun cellRangeProblem(cell: Cell): String? = when {
        cell.note != Cell.EMPTY && cell.note != Cell.CUT && cell.note !in NesSystem.noteRange ->
            "note ${cell.note} is outside C-1..B-8 (${NesSystem.noteRange.first}-${NesSystem.noteRange.last})"
        cell.instrument !in 0..NesSystem.SLOTS -> "instrument ${cell.instrument} is outside 0-${NesSystem.SLOTS}"
        cell.effect !in 0..35 -> "effect ${cell.effect} is outside 0-Z"
        cell.param !in 0..255 -> "effect parameter ${cell.param} is outside 00-FF"
        cell.more.isNotEmpty() -> "${cell.more.size + 1} effect columns; the NES plays one per row"
        cell.hasEffect && effect(cell.effect) == null -> "effect ${effectChar(cell.effect)} does not exist on the NES"
        else -> null
    }

    /**
     * The first reason [cell] cannot be played/exported on [channel], or null. Assumes [cellRangeProblem] is null.
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        val expansion = song.nes.expansion
        val channelName = channelName(expansion, channel)
        if (cell.note == Cell.CUT && cell.instrument != 0) return "OFF cannot load instrument ${hex2(cell.instrument)}; clear the instrument"
        if (cell.note == Cell.EMPTY && cell.instrument != 0) {
            return "instrument ${hex2(cell.instrument)} without a note does nothing; add a note or clear the instrument"
        }
        if (cell.note >= 0 && pitched(expansion, channel) && cell.note < lowestNote(expansion, channel)) {
            return "$channelName cannot play below A-1 (FamiStudio's longest period); play it an octave higher"
        }
        if (cell.note >= 0 && cell.instrument != 0) {
            val ins = song.nes.instruments.getOrNull(cell.instrument - 1)
            val chip = chip(expansion, channel)
            if (ins != null && ins.chip != chip) {
                return "instrument ${hex2(cell.instrument)} is a ${ins.chip.label} sound and $channelName plays " +
                    "${chip.label} sounds; use a ${chip.label} sound or change the sound's chip"
            }
        }
        if (cell.note >= 0 && channel == DPCM && cell.instrument != 0) {
            dpcmMappingProblem(song, cell.instrument, cell.note)?.let { return it }
        }
        if (!cell.hasEffect) return null
        val p = cell.param
        val x = p shr 4
        val y = p and 0xF
        val spec = effect(cell.effect) ?: return "effect ${effectChar(cell.effect)} does not exist on the NES"
        val fx = effectText(cell.effect, p)
        if (cell.effect in unusable(expansion, channel)) return "${spec.name.lowercase()} ($fx) has no effect on $channelName"
        val noteName = if (cell.note >= 0) NesSystem.noteName(cell.note) else null
        return when (cell.effect) {
            FX_ARPEGGIO -> if (cell.note < 0) "$fx needs a note on its row (FamiStudio arpeggios belong to a note)" else null
            FX_VIBRATO -> when {
                p != 0 && (x == 0 || y == 0) -> "$fx has speed $x and depth $y; set both digits (4xy, x 1-C, y 1-F) or use 400 to stop"
                x > VIBRATO_SPEED_MAX -> "$fx has speed ${NumberText.hex(x)}; FamiStudio speeds are 1-C"
                else -> null
            }
            FX_VOLUME_SLIDE -> if (x == y) "$fx slides from volume $x to the same volume; use C0${NumberText.hex(x)}" else null
            FX_VOLUME -> if (p > 15) "$fx: volumes are 00-0F" else null
            FX_SPEED -> if (p !in SPEEDS) "$fx: speeds are F01-F1F (FamiStudio has no tempo effect)" else null
            FX_NOTE_DELAY -> when {
                p == 0 -> "G00 delays nothing; use G01-G1F or remove it"
                p > DELAY_MAX -> "$fx: delays are G01-G1F frames"
                cell.note == Cell.EMPTY -> "$fx needs a note or OFF on its row"
                else -> null
            }
            FX_FINE_PITCH -> if (p == 0) "P00 is out of range; fine pitch is P01-PFF (P80 = none)" else null
            FX_SLIDE_UP, FX_SLIDE_DOWN -> when {
                cell.note < 0 -> "$fx needs a note on its row: the note glides from there"
                p == 0 -> "$fx slides 0 semitones; use 01 or more"
                else -> {
                    val target = cell.note + if (cell.effect == FX_SLIDE_UP) p else -p
                    when {
                        target !in NesSystem.noteRange -> "$fx from $noteName would end outside C-1..B-8"
                        pitched(expansion, channel) && target < lowestNote(expansion, channel) ->
                            "$fx from $noteName would end below A-1, which $channelName cannot play"
                        else -> null
                    }
                }
            }
            FX_NOTE_CUT -> if (p > DELAY_MAX) "$fx: cuts are S00-S1F frames" else null
            FX_DUTY -> when {
                kind(expansion, channel) == ChannelKind.NOISE && p > 1 -> "$fx: Noise modes are V00 (hiss) and V01 (metallic)"
                p > dutyMax(expansion, channel) -> "$fx: duties are V00-V0${dutyMax(expansion, channel)}"
                else -> null
            }
            FX_DELTA_COUNTER -> if (p > DELTA_COUNTER_MAX) "$fx: DMC levels are Z00-Z7F" else null
            else -> null
        }
    }

    /**
     * Why DPCM note [note] cannot play with instrument [slot] (1-based), or null: the instrument must map a sample to
     * the note, and the mapping must name an existing sample.
     */
    fun dpcmMappingProblem(song: Song, slot: Int, note: Int): String? {
        val name = NesSystem.noteName(note)
        val mapping = song.nes.instruments.getOrNull(slot - 1)?.dpcm?.get(note)
            ?: return "instrument ${hex2(slot)} maps no DPCM sample to $name; map one in its DPCM samples or play another note"
        if (mapping.sample !in song.nes.samples.indices) return "instrument ${hex2(slot)} maps $name to sample ${mapping.sample + 1}, which does not exist"
        return null
    }

    fun describeEffect(expansion: NesExpansion, channel: Int, effect: Int, param: Int): String {
        val x = param shr 4
        val y = param and 0xF
        val spec = effect(effect) ?: return "Unknown effect"
        if (effect in unusable(expansion, channel) && (effect != FX_ARPEGGIO || param != 0)) {
            return "${spec.name}: no effect on ${channelName(expansion, channel)}"
        }
        return when (effect) {
            FX_ARPEGGIO -> if (param == 0) "No effect" else "Arpeggio: note, +$x, +$y semitones"
            FX_VIBRATO -> if (param == 0) "Vibrato off" else "Vibrato speed $x, depth $y"
            FX_VOLUME_SLIDE -> "Volume $x sliding to $y"
            FX_VOLUME -> "Volume $param/15"
            FX_SPEED -> "Speed $param ticks per row"
            FX_NOTE_DELAY -> "Delay note by $param frames"
            FX_FINE_PITCH -> (param - FINE_CENTRE).let { if (it == 0) "Fine pitch: none" else "Fine pitch ${if (it > 0) "+" else ""}$it units" }
            FX_SLIDE_UP -> "Glide up $param semitones by the next note"
            FX_SLIDE_DOWN -> "Glide down $param semitones by the next note"
            FX_NOTE_CUT -> if (param == 0) "Cut immediately" else "Cut after $param frames"
            FX_DUTY -> when {
                kind(expansion, channel) == ChannelKind.NOISE -> if (param == 1) "Noise metallic" else "Noise hiss"
                chip(expansion, channel) == NesChip.VRC6 -> "Duty ${dutyText(NesChip.VRC6, param)}"
                else -> "Duty ${dutyText(NesChip.APU, param)}"
            }
            FX_DELTA_COUNTER -> "DMC level $param"
            else -> "Unknown effect"
        }
    }

    /** A pulse duty as the editor shows it: 2A03 "12.5%"..; VRC6 "1/16".."8/16". */
    fun dutyText(chip: NesChip, value: Int): String = when (chip) {
        NesChip.APU -> listOf("12.5%", "25%", "50%", "75%").getOrElse(value) { "?" }
        NesChip.VRC6 -> if (value in NesChip.VRC6.duty) "${value + 1}/16" else "?"
    }
}
