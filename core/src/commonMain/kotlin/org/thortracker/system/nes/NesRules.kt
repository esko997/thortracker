package org.thortracker.system.nes

import org.thortracker.backend.FamiStudioLowering
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.ChannelKind
import org.thortracker.system.EffectSpec
import kotlin.math.round

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
     * The lowest note the 2A03's pulses and triangle can play: FamiStudio note 10 (A-1), the longest 11-bit period in
     * its note table (the triangle sounds it an octave lower). The VRC6's 12-bit periods and the EPSM's SSG periods and
     * FM frequency numbers reach every note they have.
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

    /** The EPSM's SSG squares (SSG 1..3 are channels 5..7); FamiStudio's engine reads envelope periods on the first. */
    const val EPSM_SSG1 = 5

    /** The EPSM's first FM channel (FM 1..6 are channels 8..13). */
    const val EPSM_FM1 = 8

    /** The EPSM's first rhythm channel (Kick, Snare, Cymbal, Hi-hat, Tom, Rim are channels 14..19). */
    const val EPSM_RHYTHM1 = 14

    /** Channel [ch]'s name in a song with [expansion]. */
    fun channelName(expansion: NesExpansion, ch: Int): String =
        if (ch < APU_CHANNELS) APU_NAMES[ch] else expansion.channelNames.getOrElse(ch - APU_CHANNELS) { "Channel $ch" }

    /** Channel [ch]'s hardware kind in a song with [expansion]. */
    fun kind(expansion: NesExpansion, ch: Int): ChannelKind =
        if (ch < APU_CHANNELS) APU_KINDS[ch] else expansion.channelKinds[ch - APU_CHANNELS]

    /** True for the VRC6 saw of a VRC6 song. */
    fun isVrc6Saw(expansion: NesExpansion, ch: Int): Boolean = expansion == NesExpansion.VRC6 && ch == VRC6_SAW

    /** True for the three SSG squares of an EPSM song. */
    fun isEpsmSsg(expansion: NesExpansion, ch: Int): Boolean = expansion == NesExpansion.EPSM && ch in EPSM_SSG1 until EPSM_FM1

    /** True for the six FM channels of an EPSM song. */
    fun isEpsmFm(expansion: NesExpansion, ch: Int): Boolean = expansion == NesExpansion.EPSM && ch in EPSM_FM1 until EPSM_RHYTHM1

    /** True for the six rhythm (fixed drum) channels of an EPSM song. */
    fun isEpsmRhythm(expansion: NesExpansion, ch: Int): Boolean = expansion == NesExpansion.EPSM && ch >= EPSM_RHYTHM1

    /**
     * True when [song] is an EPSM song whose played orders (parked ones excluded) hold a note on a rhythm channel: the
     * song then needs the rhythm ROM to play ([org.thortracker.system.fm.Ym2608RhythmRom]).
     */
    fun playsRhythm(song: Song): Boolean {
        val sound = song.sound as? NesSound ?: return false
        if (sound.expansion != NesExpansion.EPSM) return false
        val arranged = NesSystem.arranged(song)
        for (p in arranged.orders.toSet()) {
            val pattern = arranged.patterns.getOrNull(p) ?: continue
            for (ch in EPSM_RHYTHM1 until pattern.tracks.size) {
                val track = pattern.tracks[ch]
                for (row in 0 until minOf(pattern.length, track.size)) if (track[row].note >= 0) return true
            }
        }
        return false
    }

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

    /** EPSM SSG: the shared envelope generator's period, high byte (`Ixx`) and low byte (`Jxx`), FamiTracker's letters. */
    const val FX_ENV_PERIOD_HI = 18 // I
    const val FX_ENV_PERIOD_LO = 19 // J

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
        EffectSpec(FX_ENV_PERIOD_HI, "Env period high", "xx: period xx00 (with Jyy on the row: xxyy)",
            "EPSM SSG squares only. Sets the SSG's shared envelope period (FamiStudio's EnvelopePeriod) to xx × 256, or to " +
                "xxyy with a Jyy on another SSG square in the same row; it drives sounds with a manual period until a note " +
                "reloads its sound's own. A larger period is a slower envelope.", true),
        EffectSpec(FX_ENV_PERIOD_LO, "Env period low", "yy: period 00yy (with Ixx on the row: xxyy)",
            "EPSM SSG squares only. Sets the SSG's shared envelope period to yy (00-FF: fast envelopes, audible buzzes), or " +
                "to xxyy with an Ixx on another SSG square in the same row.", true),
    )

    /** The duty effect on the VRC6 pulses: eight duties (FamiStudio's `Channel.SupportsEffect`; not on the saw). */
    private val VRC6_DUTY = EffectSpec(FX_DUTY, "Duty", "xx: 00-07 (1/16-8/16)",
        "VRC6 pulse duty from this row on: 00 = 1/16 (6.25%) up to 07 = 8/16 (50%) (an instrument duty envelope takes over " +
            "at its next step). Not on the saw.", true)

    /**
     * Fine pitch on the EPSM's FM channels: the engine adds FamiStudio's fine pitch × 8 to the note's frequency number,
     * so a cell's Pxx is converted ([epsmFmFinePitch]) to the step nearest the detune the same Pxx gives a 2A03 pulse on
     * that note: one Pxx means one pitch on every channel, and tunings work on FM too (coarser low down).
     */
    private val EPSM_FM_FINE = EffectSpec(FX_FINE_PITCH, "Fine pitch", "xx: 80 = none, 81-FF up, 01-7F down",
        "Detune the playing note until the next note by the pitch xx-80 period units give a pulse on that note (above 80 " +
            "raises it); FM plays the nearest of its frequency steps (fine high up, coarse in the lowest octaves). Tuned notes use it.", true)

    /** Fine pitch on the EPSM's SSG squares: converted per note ([epsmSsgFinePitch]) so one Pxx is one pitch everywhere. */
    private val EPSM_SSG_FINE = EffectSpec(FX_FINE_PITCH, "Fine pitch", "xx: 80 = none, 81-FF up, 01-7F down",
        "Detune the playing note until the next note by the pitch xx-80 period units give a pulse on that note (above 80 " +
            "raises it), in the SSG's own periods. Tuned notes use it.", true)

    private val BY_CODE = EFFECTS.associateBy { it.code }

    fun effect(code: Int): EffectSpec? = BY_CODE[code]

    /** The chip whose instruments [channel] plays in a song with [expansion]. */
    fun chip(expansion: NesExpansion, channel: Int): NesChip = if (channel < APU_CHANNELS) NesChip.APU else expansion.chip

    /**
     * True for the channels whose notes are pitches (pulses, triangle, the VRC6 saw, the EPSM's SSG squares and FM
     * channels); not Noise, DPCM or the EPSM's fixed-pitch drums.
     */
    fun pitched(expansion: NesExpansion, channel: Int): Boolean = kind(expansion, channel).let {
        it == ChannelKind.PULSE || it == ChannelKind.TRIANGLE || it == ChannelKind.SAW || it == ChannelKind.VOICE
    }

    /** The lowest note [channel] plays: A-1 on the 2A03's pulses and triangle, every note elsewhere. */
    fun lowestNote(expansion: NesExpansion, channel: Int): Int =
        if (channel < APU_CHANNELS && pitched(expansion, channel)) LOWEST_TONAL else 1 + NOTE_OFFSET

    /** The highest note [channel] plays: B-8, but B-7 on the EPSM's SSG and FM channels (FamiStudio's B-8 sounds B-7 there). */
    fun highestNote(expansion: NesExpansion, channel: Int): Int =
        NOTE_COUNT + NOTE_OFFSET - engineNoteShift(expansion, channel)

    /**
     * The semitones FamiStudio's note byte lies above the absolute note's plain byte (note − [NOTE_OFFSET]) on [channel]:
     * FamiStudio's EPSM note tables (`famistudio_epsm_s_note_table`, `famistudio_epsm_note_table`, at the EPSM's 8 MHz)
     * sound each FamiStudio note an octave below its name on the SSG and FM channels (note 58, "A4", plays 440 Hz), so
     * Thor Tracker's true-pitch notes take FamiStudio's note 12 higher there; 0 elsewhere (the rhythm drums ignore pitch).
     */
    fun engineNoteShift(expansion: NesExpansion, channel: Int): Int =
        if (isEpsmSsg(expansion, channel) || isEpsmFm(expansion, channel)) 12 else 0

    /** FamiStudio's note byte for absolute [note] on [channel] of a song with [expansion] ([engineNoteShift]). */
    fun engineNote(expansion: NesExpansion, channel: Int, note: Int): Int = note - NOTE_OFFSET + engineNoteShift(expansion, channel)

    /** The 2A03 pulse period + 1 of FamiStudio note [fsNote] (1..96), which one fine unit is measured against. */
    private fun pulsePeriod(fsNote: Int): Double = FamiStudioLowering.NOTE_TABLE[fsNote.coerceIn(1, NOTE_COUNT)] + 1.0

    /**
     * FamiStudio's fine pitch on an EPSM FM channel for a Pxx of [fine] (xx - 80, positive = up) on the absolute note
     * whose plain FamiStudio byte is [fsNote] (the pulse's note): the step (the engine adds it × 8 to the FM table value
     * of the note, `FAMISTUDIO_EPSM_PITCH_SHIFT`, so positive is up) nearest to the pitch [fine] period units give a pulse
     * on that note (period p + 1: ratio (p + 1) / (p + 1 - fine)), within FamiStudio's -127..127. Bends beyond the
     * pulse's period (high notes) take the largest step up.
     */
    fun epsmFmFinePitch(fsNote: Int, fine: Int): Int {
        if (fine == 0) return 0
        val p = pulsePeriod(fsNote)
        if (fine >= p) return 127
        val ratio = p / (p - fine)
        val f = FamiStudioLowering.NOTE_TABLE_EPSM_FM[(fsNote + 12).coerceIn(1, NOTE_COUNT)].toDouble()
        return round(f * (ratio - 1.0) / 8.0).toInt().coerceIn(-127, 127)
    }

    /**
     * A Pxx of [fine] (xx - 80, positive = up) on an EPSM SSG square playing the absolute note whose plain FamiStudio
     * byte is [fsNote]: the period units, positive = up, that bend the SSG's tone period (`famistudio_epsm_s_note_table`
     * at FamiStudio's note + 12) by the ratio [fine] units give a pulse on that note (period p + 1). The engine adds
     * FamiStudio's fine pitch to the period, so the stored value is this negated.
     */
    fun epsmSsgFinePitch(fsNote: Int, fine: Int): Int {
        if (fine == 0) return 0
        val square = FamiStudioLowering.NOTE_TABLE_EPSM_SSG[(fsNote + 12).coerceIn(1, NOTE_COUNT)].toDouble()
        return round(square * fine / pulsePeriod(fsNote)).toInt().coerceIn(-127, 127)
    }

    /** The highest duty the duty effect (`Vxx`) takes on [channel]: the VRC6 pulses have eight. */
    fun dutyMax(expansion: NesExpansion, channel: Int): Int =
        if (chip(expansion, channel) == NesChip.VRC6) NesChip.VRC6.duty.last else 3

    /** The SSG's envelope period effects, which exist only on the EPSM's SSG squares. */
    private val ENV_PERIOD = setOf(FX_ENV_PERIOD_HI, FX_ENV_PERIOD_LO)

    /** What FamiStudio's `Channel.SupportsEffect` allows on the EPSM rhythm channels: volume, slides of it, speed, delay, cut. */
    private val RHYTHM_EFFECTS = setOf(FX_VOLUME, FX_VOLUME_SLIDE, FX_SPEED, FX_NOTE_DELAY, FX_NOTE_CUT)

    /**
     * Effects with no meaning on [channel]: pitch effects on Noise, volume and duty on the Triangle, duty on the VRC6
     * saw and every EPSM channel, everything but speed, delay, cut and the DMC level on DPCM and everything but volume,
     * volume slides, speed, delay and cut on the EPSM's fixed-pitch drums (FamiStudio's `Channel.SupportsEffect`; its
     * arpeggios and slide notes would only re-trigger a fixed drum), the DMC level elsewhere, and the envelope period
     * off the SSG squares.
     */
    private fun unusable(expansion: NesExpansion, channel: Int): Set<Int> = when {
        isEpsmRhythm(expansion, channel) -> EFFECTS.map { it.code }.toSet() - RHYTHM_EFFECTS
        isEpsmSsg(expansion, channel) -> setOf(FX_DUTY, FX_DELTA_COUNTER)
        else -> ENV_PERIOD + when (kind(expansion, channel)) {
            ChannelKind.NOISE -> setOf(FX_ARPEGGIO, FX_VIBRATO, FX_FINE_PITCH, FX_DELTA_COUNTER)
            ChannelKind.TRIANGLE -> setOf(FX_VOLUME, FX_VOLUME_SLIDE, FX_DUTY, FX_DELTA_COUNTER)
            ChannelKind.SAMPLE -> EFFECTS.map { it.code }.toSet() - setOf(FX_SPEED, FX_NOTE_DELAY, FX_NOTE_CUT, FX_DELTA_COUNTER)
            ChannelKind.SAW, ChannelKind.VOICE -> setOf(FX_DUTY, FX_DELTA_COUNTER)
            else -> setOf(FX_DELTA_COUNTER)
        }
    }

    fun effectsFor(expansion: NesExpansion, channel: Int): List<EffectSpec> = unusable(expansion, channel).let { no ->
        val chip = chip(expansion, channel)
        EFFECTS.filter { it.code !in no }.map {
            when {
                it.code == FX_DUTY && chip == NesChip.VRC6 -> VRC6_DUTY
                it.code == FX_FINE_PITCH && isEpsmFm(expansion, channel) -> EPSM_FM_FINE
                it.code == FX_FINE_PITCH && isEpsmSsg(expansion, channel) -> EPSM_SSG_FINE
                else -> it
            }
        }
    }

    fun hex2(value: Int) = NumberText.hex(value, 2)

    /** The one-character effect name of [code] (0-9, A-Z). */
    fun effectChar(code: Int): Char = if (code < 10) '0' + code else 'A' + (code - 10)

    fun effectText(effect: Int, param: Int) = "${effectChar(effect)}${hex2(param)}"

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
        if (cell.note >= 0 && cell.note > highestNote(expansion, channel)) {
            return "$channelName cannot play above ${NesSystem.noteName(highestNote(expansion, channel))} (FamiStudio's highest note sounds B-7 there)"
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
        if (cell.effect in unusable(expansion, channel)) {
            return if (isEpsmRhythm(expansion, channel)) {
                "${spec.name.lowercase()} ($fx) has no effect on $channelName (a fixed-pitch drum: volume, speed, delay and cut only)"
            } else "${spec.name.lowercase()} ($fx) has no effect on $channelName"
        }
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
                        target > highestNote(expansion, channel) -> "$fx from $noteName would end above ${NesSystem.noteName(highestNote(expansion, channel))}"
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
            FX_FINE_PITCH -> (param - FINE_CENTRE).let {
                when {
                    it == 0 -> "Fine pitch: none"
                    chip(expansion, channel) == NesChip.EPSM -> "Fine pitch ${if (it > 0) "+" else ""}$it units (as on a pulse)"
                    else -> "Fine pitch ${if (it > 0) "+" else ""}$it units"
                }
            }
            FX_SLIDE_UP -> "Glide up $param semitones by the next note"
            FX_SLIDE_DOWN -> "Glide down $param semitones by the next note"
            FX_NOTE_CUT -> if (param == 0) "Cut immediately" else "Cut after $param frames"
            FX_DUTY -> when {
                kind(expansion, channel) == ChannelKind.NOISE -> if (param == 1) "Noise metallic" else "Noise hiss"
                chip(expansion, channel) == NesChip.VRC6 -> "Duty ${dutyText(NesChip.VRC6, param)}"
                else -> "Duty ${dutyText(NesChip.APU, param)}"
            }
            FX_DELTA_COUNTER -> "DMC level $param"
            FX_ENV_PERIOD_HI -> "SSG envelope period ${NumberText.hex(param, 2)}00 (${param shl 8}; a Jyy in the row sets the low byte)"
            FX_ENV_PERIOD_LO -> "SSG envelope period 00${NumberText.hex(param, 2)} ($param; an Ixx in the row sets the high byte)"
            else -> "Unknown effect"
        }
    }

    /** A pulse duty as the editor shows it: 2A03 "12.5%"..; VRC6 "1/16".."8/16"; EPSM sounds have none. */
    fun dutyText(chip: NesChip, value: Int): String = when (chip) {
        NesChip.APU -> listOf("12.5%", "25%", "50%", "75%").getOrElse(value) { "?" }
        NesChip.VRC6 -> if (value in NesChip.VRC6.duty) "${value + 1}/16" else "?"
        NesChip.EPSM -> "none"
    }

    /**
     * The SSG envelope period each row of [cells] (one row of every SSG square, in channel order) sets, or -1: `Ixx`
     * gives the high byte and `Jyy` the low byte, on any squares of the row; a byte without its effect is 0. Null when
     * two squares give one byte different values (the row is ambiguous; [envPeriodConflict] says why).
     */
    fun envPeriod(cells: List<Cell>): Int? {
        var hi = -1
        var lo = -1
        for (c in cells) {
            if (!c.hasEffect) continue
            when (c.effect) {
                FX_ENV_PERIOD_HI -> if (hi >= 0 && hi != c.param) return null else hi = c.param
                FX_ENV_PERIOD_LO -> if (lo >= 0 && lo != c.param) return null else lo = c.param
            }
        }
        return if (hi < 0 && lo < 0) -1 else (maxOf(hi, 0) shl 8) or maxOf(lo, 0)
    }

    /** Why one row's SSG cells set the envelope period ambiguously, or null. */
    fun envPeriodConflict(cells: List<Cell>): String? {
        if (envPeriod(cells) != null) return null
        val hi = cells.filter { it.hasEffect && it.effect == FX_ENV_PERIOD_HI }.map { it.param }.distinct()
        val which = if (hi.size > 1) hi.map { effectText(FX_ENV_PERIOD_HI, it) } else
            cells.filter { it.hasEffect && it.effect == FX_ENV_PERIOD_LO }.map { it.param }.distinct().map { effectText(FX_ENV_PERIOD_LO, it) }
        return "${which.joinToString(" and ")} set the SSG's one envelope period twice in one row; keep one"
    }
}
