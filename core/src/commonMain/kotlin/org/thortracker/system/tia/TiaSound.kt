package org.thortracker.system.tia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.backend.TiaPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * The TV standard an Atari 2600 song plays on: the TIA is clocked from the colour clock (NTSC 3579545.45 Hz, PAL
 * 3546894 Hz) and the player runs once per frame (262 or 312 scanlines of 228 colour clocks), so the region sets both
 * every waveform's pitch and the tick rate. Each region is its own system ([TiaNtscSystem], [TiaPalSystem]).
 */
@Serializable
enum class TiaRegion(val code: Int) {
    @SerialName("ntsc") NTSC(TiaPlayerImage.REGION_NTSC),
    @SerialName("pal") PAL(TiaPlayerImage.REGION_PAL);

    /** The system songs of this region belong to. */
    val system: TiaSystem get() = if (this == NTSC) TiaNtscSystem else TiaPalSystem

    /** "NTSC", "PAL". */
    val label: String get() = name
}

/**
 * One instrument envelope: a value per player tick (one TV frame). Playback runs through the values, then jumps back to
 * [loop] (a value index) or holds the last value when [loop] is -1. [release] is where a released note (OFF) continues:
 * while the note is held only `[0, release)` plays (looping to [loop] inside it), after OFF the values from [release] on
 * play once and hold the last. A volume envelope with a release point makes OFF a release instead of a stop. Empty =
 * not used (the defaults: volume 15, no arpeggio, no pitch offset, the instrument's own waveform).
 */
@Serializable
data class TiaEnvelope(val values: List<Int> = emptyList(), val loop: Int = -1, val release: Int = -1) {
    val isEmpty: Boolean get() = values.isEmpty()

    /**
     * Why the envelope cannot load with values in [range], or null: at most [MAX_LENGTH] values, [loop] -1 or a value
     * index, [release] -1 or 1..length − 1 and after [loop] when both are set (the loader's envelope shape checks).
     */
    fun problem(range: IntRange): String? {
        val n = values.size
        return when {
            n > MAX_LENGTH -> "has $n steps; envelopes have at most $MAX_LENGTH"
            n == 0 && (loop != -1 || release != -1) -> "has a loop or release point but no steps"
            loop != -1 && loop !in 0 until n -> "loop point $loop is outside its $n steps"
            release != -1 && release !in 1 until n -> "release point $release must be 1-${n - 1} (a step after the first)"
            loop != -1 && release != -1 && release <= loop -> "release point $release must come after the loop point $loop"
            else -> values.firstOrNull { it !in range }?.let { "step value $it is outside ${range.first}..${range.last}" }
        }
    }

    companion object {
        /** The player's longest envelope (a u8 length). */
        const val MAX_LENGTH = TiaPlayerImage.MAX_ENVELOPE

        /** Volume steps: AUDV 0 silent .. 15 full. */
        val VOLUME = 0..TiaPlayerImage.VOLUME_MAX

        /** Arpeggio steps: semitones added to the note (raw sounds: divider steps). */
        val ARPEGGIO = -64..63

        /** Pitch steps: 1/64 semitone (absolute per tick, or accumulated when [TiaInstrument.relativePitch]). */
        val PITCH = -128..127

        /** Waveform steps: AUDC 0..15 per tick. */
        val WAVEFORM = 0..TiaPlayerImage.WAVEFORM_MAX

        val NONE = TiaEnvelope()
    }
}

/**
 * An Atari 2600 sound: the TIA [waveform] (the AUDC value 0..15 it writes: [TiaRules.WAVEFORMS]), how its notes pick
 * the frequency divider AUDF, and four envelopes.
 *
 * - Tonal ([raw] false): the note's pitch (plus fine pitch, slides, vibrato, arpeggio and the [pitch] envelope) picks
 *   the AUDF whose frequency for the playing waveform is nearest in log frequency ([TiaRules.audf]); the note keeps
 *   that divider's true frequency, so most notes are a few cents off and each waveform reaches only its own span
 *   ([TiaRules.reach]). The constant waveforms (AUDC 0, B) write no AUDF.
 * - Raw ([raw] true, for noise and percussion): AUDF = 31 − ((note + arpeggio semitones) mod 32): C-0 is AUDF 31 and
 *   each semitone up is one divider step brighter, repeating every 32 semitones. Fine pitch, slides, portamento,
 *   vibrato and the pitch envelope do not apply.
 *
 * [volume] (0..15) is the AUDV per tick, [arpeggio] adds semitones, [pitch] adds 1/64 semitones (absolute per tick, or
 * summed tick by tick when [relativePitch]), [waveforms] picks the AUDC per tick (empty = [waveform]; while it has
 * steps `Wxx` is ignored).
 */
@Serializable
data class TiaInstrument(
    val name: String = "Sound",
    val waveform: Int = DEFAULT_WAVEFORM,
    val raw: Boolean = false,
    val relativePitch: Boolean = false,
    val volume: TiaEnvelope = TiaEnvelope(),
    val arpeggio: TiaEnvelope = TiaEnvelope(),
    val pitch: TiaEnvelope = TiaEnvelope(),
    val waveforms: TiaEnvelope = TiaEnvelope(),
) {
    /** Why a field is out of its range (the loader's instrument checks), or null. */
    fun problem(): String? {
        volume.problem(TiaEnvelope.VOLUME)?.let { return "volume envelope $it" }
        arpeggio.problem(TiaEnvelope.ARPEGGIO)?.let { return "arpeggio envelope $it" }
        pitch.problem(TiaEnvelope.PITCH)?.let { return "pitch envelope $it" }
        waveforms.problem(TiaEnvelope.WAVEFORM)?.let { return "waveform envelope $it" }
        return if (waveform !in TiaEnvelope.WAVEFORM) "waveform $waveform is outside 0-${TiaPlayerImage.WAVEFORM_MAX} (AUDC 0-F)" else null
    }

    /** The waveform a note keys on with: the waveform envelope's first step, else [override] (`Wxx`), else [waveform]. */
    fun keyOnWaveform(override: Int = -1): Int = waveforms.values.firstOrNull() ?: if (override >= 0) override else waveform

    companion object {
        /** A new sound's waveform: AUDC C, the div 6 square (E-3 .. D#8 NTSC, the TIA's most melodic span). */
        const val DEFAULT_WAVEFORM = 12

        /** A new slot: a flat full-volume div 6 square. */
        val DEFAULT = TiaInstrument()
    }
}

/**
 * An Atari 2600 song's chip data: its [region] (which of the two systems it belongs to) and [instruments] = exactly
 * [TiaSystem.SLOTS] slots (slot n = index n − 1; null = empty). The TIA has nothing else to store: no wavetables, no
 * samples (it has no DAC; see docs/BACKENDS.md) and no shared registers.
 */
@Serializable
data class TiaSound(
    val region: TiaRegion,
    val instruments: List<TiaInstrument?>,
) : SystemSound {
    override val system: SoundSystem get() = region.system

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): TiaInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: TiaInstrument?): TiaSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Why a song-level value is out of range, or null. */
    fun problem(): String? =
        if (instruments.size != TiaSystem.SLOTS) "has ${instruments.size} sound slots; Atari 2600 songs have ${TiaSystem.SLOTS}" else null

    companion object {
        /** A new [region] song's sound: the presets in slots 01.. ([TiaPresets.ALL]), the rest empty. */
        fun blank(region: TiaRegion): TiaSound =
            TiaSound(region, List(TiaSystem.SLOTS) { i -> TiaPresets.ALL.getOrNull(i)?.let { it.instrument.copy(name = it.name) } })
    }
}

/** The Atari 2600 data of a TIA song. */
val Song.tia: TiaSound get() = sound as TiaSound

/** The TIA system (region) of an Atari 2600 song. */
val Song.tiaSystem: TiaSystem get() = tia.region.system
