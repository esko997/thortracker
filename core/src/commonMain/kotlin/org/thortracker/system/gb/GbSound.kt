package org.thortracker.system.gb

import kotlinx.serialization.Serializable
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/** The Game Boy's three typed instrument banks (bank index = ordinal), named after the channels that play them. */
@Serializable
enum class GbBank { PULSE, WAVE, NOISE }

/**
 * A Game Boy sound: hUGEDriver instrument register settings. Only the fields relevant to [kind] are audible; the rest
 * are preserved.
 *
 * - envelopeDirection / sweepDirection: 0 = decrease, 1 = increase.
 * - length: raw length-timer register value (pulse/noise 0..63, wave 0..255); sound lasts (64 - length)/256 s
 *   (wave: (256 - length)/256 s) when [lengthEnabled].
 * - sweepTime 0..7 (0 = off), sweepShift 0..7: pulse frequency sweep, hardware exists on Pulse 1 only.
 * - wave 0..15: waveform index; waveVolume: 1 = 100%, 2 = 50%, 3 = 25%, 0 = mute.
 * - noiseWidth: 0 = 15-bit (long) LFSR, 1 = 7-bit (short, metallic) LFSR.
 */
@Serializable
data class GbInstrument(
    val name: String = "Sound",
    val kind: GbBank = GbBank.PULSE,
    val duty: Int = 2,
    val volume: Int = 12,
    val envelopeDirection: Int = 0,
    val envelopePace: Int = 0,
    val length: Int = 0,
    val lengthEnabled: Boolean = false,
    val sweepTime: Int = 0,
    val sweepDirection: Int = 0,
    val sweepShift: Int = 0,
    val wave: Int = 0,
    val waveVolume: Int = 1,
    val noiseWidth: Int = 0,
) {
    /** True when the pulse frequency sweep actually changes pitch (needs Pulse 1 hardware). */
    val usesSweep: Boolean get() = kind == GbBank.PULSE && sweepTime != 0 && sweepShift != 0
}

/**
 * A Game Boy song's chip data: typed banks of exactly 15 instruments (slot n = index n-1) and exactly 16 waveforms
 * of 32 samples 0..15. Stored in project files as the song's `sound` object.
 */
@Serializable
data class GbSound(
    val pulse: List<GbInstrument>,
    val wave: List<GbInstrument>,
    val noise: List<GbInstrument>,
    val waves: List<List<Int>>,
) : SystemSound {
    override val system: SoundSystem get() = GbSystem
}

/** This Game Boy song's chip data. Only Game Boy code calls it, on Game Boy songs. */
val Song.gb: GbSound get() = sound as GbSound
