package org.thortracker.system.nes

import kotlinx.serialization.Serializable
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * One FamiStudio instrument envelope: a value per engine frame (60.1 Hz). Playback runs through the values, then
 * jumps back to [loop] (a value index) or holds the last value when [loop] is -1. [release] (volume only) is where
 * a released note (OFF) continues: the loop then runs from [loop] to just before [release]. Empty = the envelope is
 * not used (FamiStudio's defaults: volume 15, no arpeggio, no pitch offset, duty untouched).
 * Ranges and limits are FamiStudio 4.5.3's (`Envelope.cs`); see [NesSystem] for the checks.
 */
@Serializable
data class NesEnvelope(val values: List<Int> = emptyList(), val loop: Int = -1, val release: Int = -1) {
    companion object {
        /** FamiStudio's longest envelope. */
        const val MAX_LENGTH = 256
        val VOLUME = 0..15
        val ARPEGGIO = -64..63
        val PITCH = -64..63

        /** 12.5%, 25%, 50%, 75% on the pulses; on Noise 0 = long (hiss), 1 = short (metallic) LFSR. */
        val DUTY = 0..3
    }
}

/**
 * A FamiStudio 2A03 instrument: four envelopes. [arpeggio] offsets the note in semitones, [pitch] in note-table
 * period units (absolute per frame, or accumulated each frame when [pitchRelative]), [duty] sets the pulse duty or
 * the noise mode. The triangle ignores [duty] and plays any [volume] above 0 at full level.
 */
@Serializable
data class NesInstrument(
    val name: String = "Sound",
    val volume: NesEnvelope = NesEnvelope(listOf(15)),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val duty: NesEnvelope = NesEnvelope(),
) {
    companion object {
        /** A new slot: flat full volume, nothing else. */
        val DEFAULT = NesInstrument()
    }
}

/**
 * An NES song's chip data: one bank of exactly [NesSystem.SLOTS] instruments shared by all four channels (slot n =
 * index n-1). Stored in project files as the song's `sound` object.
 */
@Serializable
data class NesSound(val instruments: List<NesInstrument>) : SystemSound {
    override val system: SoundSystem get() = NesSystem

    companion object {
        fun blank(): NesSound = NesSound(List(NesSystem.SLOTS) { i -> NesInstrument(name = "Sound ${i + 1}") })
    }
}

/** This NES song's chip data. Only NES code calls it, on NES songs. */
val Song.nes: NesSound get() = sound as NesSound
