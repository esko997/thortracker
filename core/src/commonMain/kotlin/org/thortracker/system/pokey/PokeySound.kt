@file:OptIn(ExperimentalSerializationApi::class)

package org.thortracker.system.pokey

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.nes.NesEnvelope

/**
 * The Atari machine a POKEY song plays on: its clock and video frame (the player's tick). NTSC: 315 MHz / 176 =
 * 1.789773 MHz and 262 lines of 114 cycles (59.92 Hz); PAL: 1.773447 MHz and 312 lines (49.86 Hz). Atari 400/800
 * Hardware Manual; Altirra Hardware Reference Manual table 9.
 */
@Serializable
enum class PokeyMachine(val label: String, val clockHz: Double, val frameCycles: Int) {
    @SerialName("ntsc") NTSC("NTSC", 315_000_000.0 / 176.0, 114 * 262),
    @SerialName("pal") PAL("PAL", 1_773_447.5, 114 * 312);

    /** Video frames (player ticks at multispeed 1) per second. */
    val frameHz: Double get() = clockHz / frameCycles
}

/**
 * What a POKEY sound is, which tracker channels (0-based lanes, = POKEY channels 1-4) it may play on and which POKEY
 * channels a note of it takes (docs/AUDIO_ENGINE.md, "POKEY: channels, sounds and the channels they take"). A note
 * takes every channel in [taken], cutting whatever sound held them; [fastLanes]: where [PokeyInstrument.fast] (the
 * 1.79 MHz machine clock) is available.
 */
@Serializable
enum class PokeyKind(val label: String, val lanes: Set<Int>, val fastLanes: Set<Int>) {
    /** One 8-bit channel; fast only on channels 1 and 3. */
    @SerialName("tone") TONE("8-bit", setOf(0, 1, 2, 3), setOf(0, 2)),

    /** 16-bit: plays on channel 2 or 4, channel 1 or 3 is its low byte (AUDCTL links them). */
    @SerialName("joined") JOINED("16-bit", setOf(1, 3), setOf(1, 3)),

    /** High-pass: plays on channel 1 or 2, channel 3 or 4 clocks its high-pass flip-flop. */
    @SerialName("filtered") FILTERED("High-pass", setOf(0, 1), setOf(0)),

    /** 16-bit high-pass: channels 1+2 the sound, 3+4 its high-pass clock; plays on channel 2. */
    @SerialName("joined-filtered") JOINED_FILTERED("16-bit high-pass", setOf(1), setOf(1)),

    /** 4-bit samples through volume-only mode, one level per interrupt of the channel's timer (timer 3 has none). */
    @SerialName("sample") SAMPLE("Samples", setOf(0, 1, 3), setOf(0));

    /** The POKEY channels (0-based) a note of this kind on [lane] takes, its own first; empty if it cannot play there. */
    fun taken(lane: Int): List<Int> = if (lane !in lanes) emptyList() else when (this) {
        TONE, SAMPLE -> listOf(lane)
        JOINED -> listOf(lane, lane - 1)
        FILTERED -> listOf(lane, lane + 2)
        JOINED_FILTERED -> listOf(1, 0, 3, 2)
    }

    val joined: Boolean get() = this == JOINED || this == JOINED_FILTERED
    val filtered: Boolean get() = this == FILTERED || this == JOINED_FILTERED
}

/**
 * The POKEY's eight AUDC distortions plus volume only, as envelopes and `Vxx` number them: value v < 8 is AUDC bits 7-5
 * = v, named by the AUDC high nibble as Atari musicians do ("dist A" pure tone, "dist C" the 4-bit poly buzz); 8 =
 * volume only (the level is a DC step: the DAC).
 */
object PokeyDistortion {
    const val PURE = 5
    const val VOLUME_ONLY = 8
    val RANGE = 0..8
    val NAMES = listOf("0", "2", "4", "6", "8", "A", "C", "E", "V")

    /** AUDC's upper bits for distortion [v] (volume only: bit 4). */
    fun audcBits(v: Int): Int = if (v == VOLUME_ONLY) 0x10 else (v and 7) shl 5

    /** What distortion [v] sounds like, by the AUDC name ("A pure tone", "C buzz"); "?" outside [RANGE]. */
    fun label(v: Int): String = DESCRIPTIONS.getOrElse(v) { "?" }

    private val DESCRIPTIONS = listOf(
        "0 noise (5- and 17/9-bit)", "2 gated tone (5-bit)", "4 buzz (5- and 4-bit)", "6 gated tone (5-bit)",
        "8 noise (17/9-bit)", "A pure tone", "C buzz (4-bit)", "E pure tone", "volume only",
    )

    /** Noise distortions (`0`, `8`) and volume only: their notes pick a rate, not a pitched waveform. */
    fun pitched(v: Int): Boolean = v != 0 && v != 4 && v != VOLUME_ONLY
}

/**
 * A POKEY sound. Envelopes are FamiStudio-style ([NesEnvelope]: a value per player tick, loop, release; empty = not
 * used): [volume] 0..15 (empty: 15), [arpeggio] semitones −64..63, [pitch] sixteenths of a semitone −64..63 (absolute
 * offset, or added up each tick when [pitchRelative]), [distortion] [PokeyDistortion] values 0..8 (empty: pure tone).
 * [fast]: the 1.79 MHz clock where [kind] allows it ([PokeyKind.fastLanes]). High-pass kinds: [filterOffset] (the
 * clock channel's counter = the sound's + offset, −128..127; with [difference] 1..127 and the note is the difference of
 * the two counters' rates). [partnerDistortion]/[partnerVolume]: what the channels the sound takes besides its own
 * play (volume 0 = silent, the usual). [SAMPLE] sounds play song sample [sample] at [rate] levels per second on note
 * [root], looping from level [loop] (−1: once); they use [arpeggio] and [pitch] only.
 */
@Serializable
data class PokeyInstrument(
    val name: String = "",
    val kind: PokeyKind = PokeyKind.TONE,
    val fast: Boolean = false,
    val volume: NesEnvelope = NesEnvelope(listOf(15)),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val distortion: NesEnvelope = NesEnvelope(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val filterOffset: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val difference: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val partnerDistortion: Int = PokeyDistortion.PURE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val partnerVolume: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sample: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val root: Int = DEFAULT_ROOT,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val rate: Int = DEFAULT_RATE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val loop: Int = -1,
) {
    /** The partner channels' AUDC byte the image stores (0 when silent). */
    val partnerAudc: Int get() = if (partnerVolume == 0) 0 else PokeyDistortion.audcBits(partnerDistortion) or partnerVolume

    companion object {
        /** C-4: samples play at their rate on this note unless set otherwise. */
        const val DEFAULT_ROOT = 48
        const val DEFAULT_RATE = 7990
        val RATES = 1000..32000
        val FILTER_OFFSET = -128..127
        val DIFFERENCE_OFFSET = 1..127
        val VOLUME = 0..15
    }
}

/**
 * A recorded sound for [PokeyKind.SAMPLE] sounds: 4-bit levels 0..15 (the volume-only DAC), at most
 * [MAX_LENGTH] levels; all of a song's samples share [MEMORY] levels.
 */
@Serializable
data class PokeySample(val name: String, val levels: List<Int>) {
    companion object {
        const val MAX_LENGTH = 32768
        /** The image's sample region: 16 KB of two levels per byte. */
        const val MEMORY = 32768
        const val MAX_NAME = 16
    }
}

/**
 * A POKEY song's chip data: [instruments] = exactly [PokeySystem.SLOTS] slots (slot n = index n − 1; null = empty),
 * the song's [samples] (at most [PokeySystem.MAX_SAMPLES]), the [machine], [multispeed] 1..4 player ticks per video
 * frame, and the shared AUDCTL bits the song starts with ([poly9]: the 9-bit noise generator; [base15]: the 15 kHz base
 * clock; `Nxx`/`Kxx` change them while playing).
 */
@Serializable
data class PokeySound(
    val instruments: List<PokeyInstrument?>,
    val samples: List<PokeySample> = emptyList(),
    val machine: PokeyMachine = PokeyMachine.NTSC,
    val multispeed: Int = 1,
    val poly9: Boolean = false,
    val base15: Boolean = false,
) : SystemSound {
    override val system: SoundSystem get() = PokeySystem

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): PokeyInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: PokeyInstrument?): PokeySound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** The image header's shared AUDCTL byte. */
    val audctlByte: Int get() = (if (poly9) 0x80 else 0) or (if (base15) 0x01 else 0)

    companion object {
        /** A new song's sound: the presets in slots 01.. ([PokeyPresets.ALL]), the rest empty. */
        fun blank(): PokeySound = PokeySound(List(PokeySystem.SLOTS) { PokeyPresets.ALL.getOrNull(it)?.instrument })
    }
}

/** The POKEY data of a POKEY song. */
val Song.pokey: PokeySound get() = sound as PokeySound
