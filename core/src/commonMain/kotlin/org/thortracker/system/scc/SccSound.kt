package org.thortracker.system.scc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.nes.NesEnvelope

/**
 * Which Konami chip a song is for. [SCC]: the K051649 of the MSX megaROM cartridges, whose channels 4 and 5 share one
 * wave RAM (both always play what it holds). [PLUS]: the K052539 of the Sound Cartridge in SCC+ mode, one wave RAM per
 * channel. Everything else is identical (docs/AUDIO_ENGINE.md, "SCC and SCC+").
 */
@Serializable
enum class SccVariant(val label: String, val chip: String) {
    @SerialName("scc") SCC("SCC", "K051649"),
    @SerialName("scc-plus") PLUS("SCC+", "K052539");

    /** True when channels 4 and 5 (index 3 and 4) play one shared wave RAM. */
    val sharedWave: Boolean get() = this == SCC

    val system: KonamiSccSystem get() = when (this) {
        SCC -> SccSystem
        PLUS -> SccPlusSystem
    }
}

/**
 * One wavetable: exactly [SAMPLES] signed 8-bit samples (-128..127), played 0 → 31 and again. [name] is the editor's.
 */
@Serializable
data class SccWave(val name: String = "", val samples: List<Int> = List(SAMPLES) { 0 }) {
    /** Why the wave cannot be lowered, or null. */
    fun problem(): String? = when {
        samples.size != SAMPLES -> "has ${samples.size} samples; waves hold $SAMPLES"
        samples.any { it !in LEVELS } -> "has a sample outside -128..127"
        else -> null
    }

    companion object {
        const val SAMPLES = 32
        val LEVELS = -128..127
    }
}

/**
 * An SCC instrument: FamiStudio-style envelopes ([NesEnvelope]: a value per player tick, a loop point and, for
 * [volume] only, a release point) run by Thor Tracker's player.
 * [volume] 0..15 (empty = constant 15), scaled by the channel volume (`Cxx`); [arpeggio] semitone offsets -64..63
 * (empty = none); [pitch] offsets in 1/16 semitone -64..63 (empty = none), accumulated every tick when
 * [pitchRelative] (sweeps for drums); [wave] the wave numbers 1..[SccSound.WAVES] the note plays, per tick (at least one
 * value; one value = a fixed wave). [restart]: each note starts its waveform at sample 0 (the player sets the chip's
 * test-register bit 5 around the note's period write), for consistent attacks; otherwise the waveform runs on from
 * wherever the channel's counter is.
 */
@Serializable
data class SccInstrument(
    val name: String = "",
    val volume: NesEnvelope = NesEnvelope(listOf(15)),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val wave: NesEnvelope = NesEnvelope(listOf(1)),
    val restart: Boolean = false,
) {
    /** Every wave number the instrument can play (its wave envelope's values). */
    val waves: Set<Int> get() = wave.values.toSet()
}

/**
 * An SCC song's chip data: the [variant], [instruments] = exactly [SLOTS] slots (slot n = index n - 1; null = empty,
 * not playable), [waves] = exactly [WAVES] wavetables (wave n = index n - 1; an unused wave is silent), and [multispeed]
 * 1..8 player ticks per 60 Hz frame.
 */
@Serializable
data class SccSound(
    val variant: SccVariant,
    val instruments: List<SccInstrument?>,
    val waves: List<SccWave>,
    val multispeed: Int = 1,
) : SystemSound {
    override val system: SoundSystem get() = variant.system

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): SccInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: SccInstrument?): SccSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Wave [number] (1-based), or null when out of range. */
    fun wave(number: Int): SccWave? = waves.getOrNull(number - 1)

    /** Wave [number] (1-based) replaced. */
    fun withWave(number: Int, wave: SccWave): SccSound = copy(waves = waves.toMutableList().also { it[number - 1] = wave })

    companion object {
        const val SLOTS = 64
        const val WAVES = 64
        const val CHANNELS = 5

        /** Channel indexes (0-based) that share one wave RAM on the [SccVariant.SCC]: SCC 4 and SCC 5. */
        val SHARED_CHANNELS = 3..4

        /**
         * A new song's chip data for [variant]: the presets ([SccPresets.ALL]) in the first slots, the rest empty; the
         * starter waves ([SccPresets.WAVES]) as waves 1.., then silent waves up to [WAVES]; multispeed 1.
         */
        fun blank(variant: SccVariant): SccSound = SccSound(
            variant = variant,
            instruments = List(SLOTS) { SccPresets.ALL.getOrNull(it)?.instrument },
            waves = List(WAVES) { SccPresets.WAVES.getOrElse(it) { SccWave() } },
        )
    }
}

/** The SCC data of an SCC or SCC+ song. Only SCC code calls it, on SCC songs. */
val Song.scc: SccSound get() = sound as SccSound
