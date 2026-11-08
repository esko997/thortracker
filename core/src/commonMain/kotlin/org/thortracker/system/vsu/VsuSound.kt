package org.thortracker.system.vsu

import kotlinx.serialization.Serializable
import org.thortracker.backend.VsuPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * Channel 5's hardware frequency modification: S5EV1 bits 6 ([on]), 4 ([modulation]: false = sweep) and 5 ([repeat]:
 * the modulation table repeats), and S5SWP ([slowClock] bit 7: a 7.68 ms unit instead of 0.96 ms, [interval] bits 4-6:
 * 0..7, 0 = off, [up] bit 3: sweep up, [shift] bits 0-2). Ignored on every lane but Wave 5.
 */
@Serializable
data class VsuFreqMod(
    val on: Boolean = false,
    val modulation: Boolean = false,
    val repeat: Boolean = false,
    val slowClock: Boolean = false,
    val interval: Int = 0,
    val up: Boolean = false,
    val shift: Int = 0,
) {
    /** Image byte 6: on, modulation, repeat. */
    val flagsByte: Int get() = (if (on) VsuPlayerImage.FM_ON else 0) or (if (modulation) VsuPlayerImage.FM_MODULATION else 0) or
        (if (repeat) VsuPlayerImage.FM_REPEAT else 0)

    /** S5SWP as the image stores it (byte 7) and `Mxx` writes it. */
    val swpByte: Int get() = (if (slowClock) VsuPlayerImage.SWP_SLOW else 0) or (interval shl VsuPlayerImage.SWP_INTERVAL_SHIFT) or
        (if (up) VsuPlayerImage.SWP_UP else 0) or shift

    /** True when the hardware sweep or modulation runs (on, with an interval). */
    val running: Boolean get() = on && interval != 0

    /** [this] with S5SWP's fields from byte [swp] (an `Mxx` lock), keeping the function bits. */
    fun withSwp(swp: Int): VsuFreqMod = copy(
        slowClock = swp and VsuPlayerImage.SWP_SLOW != 0,
        interval = (swp shr VsuPlayerImage.SWP_INTERVAL_SHIFT) and 7,
        up = swp and VsuPlayerImage.SWP_UP != 0,
        shift = swp and VsuPlayerImage.SWP_SHIFT_MASK,
    )

    fun problem(): String? = when {
        interval !in 0..7 -> "sweep/modulation interval $interval is outside 0-7"
        shift !in 0..7 -> "sweep shift $shift is outside 0-7"
        else -> null
    }
}

/**
 * The software pitch sweep the player runs (as the OPL3's drum sweep): each key-on starts [start] semitones from the
 * note and moves toward [end] at [speed]/16 semitones per tick; [speed] 0 = none ([start]/[end] then ignored and
 * lowered as 0). Ranges −96..96, −96..96 and 0..255.
 */
@Serializable
data class VsuSweep(val start: Int = 0, val end: Int = 0, val speed: Int = 0) {
    val active: Boolean get() = speed != 0

    companion object {
        val NONE = VsuSweep()
    }
}

/**
 * A VSU instrument: the [wave] it plays (0..4 = wave 1..5; ignored on Noise), the envelope (start [level] 0..15,
 * [envelope] steps on, [grow] up instead of down, [interval] 0..7 = (n + 1) × 15.36 ms per step, [repeat] reload the
 * start level after one step at the limit), the hardware length ([autoLength], [length] 0..31 = (n + 1) × 3.84 ms),
 * the noise [noiseTap] 0..7 (Noise only), Wave 5's [freqMod] and the player's pitch [sweep].
 */
@Serializable
data class VsuInstrument(
    val name: String = "",
    val wave: Int = 0,
    val level: Int = 15,
    val envelope: Boolean = false,
    val grow: Boolean = false,
    val interval: Int = 0,
    val repeat: Boolean = false,
    val autoLength: Boolean = false,
    val length: Int = 0,
    val noiseTap: Int = 0,
    val freqMod: VsuFreqMod = VsuFreqMod(),
    val sweep: VsuSweep = VsuSweep(),
) {
    /** Image byte 0: defined, envelope on, repeat, grow, auto length. */
    val flagsByte: Int get() = VsuPlayerImage.FLAG_DEFINED or (if (envelope) VsuPlayerImage.FLAG_ENVELOPE else 0) or
        (if (repeat) VsuPlayerImage.FLAG_REPEAT else 0) or (if (grow) VsuPlayerImage.FLAG_GROW else 0) or
        (if (autoLength) VsuPlayerImage.FLAG_AUTO else 0)

    /** Why the instrument cannot be lowered, or null. */
    fun problem(): String? = when {
        wave !in 0 until VsuPlayerImage.WAVES -> "wave ${wave + 1} is outside 1-${VsuPlayerImage.WAVES}"
        level !in 0..VsuPlayerImage.LEVEL_MAX -> "level $level is outside 0-15"
        interval !in 0..VsuPlayerImage.INTERVAL_MAX -> "envelope interval $interval is outside 0-7"
        length !in 0..VsuPlayerImage.LENGTH_MAX -> "length $length is outside 0-31"
        noiseTap !in 0..VsuPlayerImage.TAP_MAX -> "noise tap $noiseTap is outside 0-7"
        sweep.start !in -VsuPlayerImage.SWEEP_LIMIT..VsuPlayerImage.SWEEP_LIMIT || sweep.end !in -VsuPlayerImage.SWEEP_LIMIT..VsuPlayerImage.SWEEP_LIMIT ->
            "pitch sweep start and end must be -96..96 semitones"
        sweep.speed !in 0..255 -> "pitch sweep speed ${sweep.speed} is outside 0-255"
        else -> freqMod.problem()
    }
}

/**
 * A Virtual Boy song's chip data: [instruments] = exactly [VsuSystem.SLOTS] slots (slot n = index n − 1; null = empty,
 * not playable), the song-wide wave RAM [waves] (exactly 5 waves of 32 samples 0..63; any lane may play any wave) and
 * the song-wide modulation RAM [modulation] (32 values −128..127, read by Wave 5's modulation).
 */
@Serializable
data class VsuSound(
    val instruments: List<VsuInstrument?>,
    val waves: List<List<Int>>,
    val modulation: List<Int>,
) : SystemSound {
    override val system: SoundSystem get() = VsuSystem

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): VsuInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: VsuInstrument?): VsuSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Wave [index] (0..4) replaced. */
    fun withWave(index: Int, samples: List<Int>): VsuSound = copy(waves = waves.toMutableList().also { it[index] = samples })

    companion object {
        /**
         * A new song's sound: the starter presets in slots 01.. ([VsuPresets.STARTERS]), the rest empty; the default
         * waves sine, triangle, saw, square and 25 % pulse; a gentle sine vibrato in the modulation table.
         */
        fun blank(): VsuSound = VsuSound(
            instruments = List(VsuSystem.SLOTS) { VsuPresets.STARTERS.getOrNull(it)?.instrument },
            waves = VsuPresets.DEFAULT_WAVES.map { it.samples },
            modulation = VsuPresets.DEFAULT_MODULATION.values,
        )
    }
}

/** The VSU data of a Virtual Boy song. */
val Song.vsu: VsuSound get() = sound as VsuSound
