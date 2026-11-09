package org.thortracker.system.opn

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.thortracker.backend.OpnPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmSweep
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What a tracker channel of an OPN-family chip is (the native `OPN_PART_*`, and the instrument kind it plays). */
enum class OpnPart { FM, SSG, RHYTHM, ADPCM }

/**
 * The two chips: the YM2203 (OPN: three FM channels, the SSG) and the YM2608 (OPNA: six FM channels with stereo and the
 * LFO, the SSG, six rhythm drums from the user-imported rhythm ROM and the ADPCM-B sample channel with 256 KiB of
 * memory), each at its PC-88 / PC-98 clock. Channel order: FM, SSG 1-3, then (YM2608) Kick, Snare, Cymbal, Hi-hat,
 * Tom, Rim and ADPCM.
 */
@Serializable
enum class OpnChip(
    /** The native image's chip byte. */
    val code: Int,
    val label: String,
    val clock: Int,
    val fmChannels: Int,
    val channelCount: Int,
) {
    @SerialName("ym2203") YM2203(OpnPlayerImage.CHIP_YM2203, "YM2203 (OPN)", OpnPlayerImage.CLOCK_YM2203, OpnPlayerImage.FM_YM2203, OpnPlayerImage.CHANNELS_YM2203),
    @SerialName("ym2608") YM2608(OpnPlayerImage.CHIP_YM2608, "YM2608 (OPNA)", OpnPlayerImage.CLOCK_YM2608, OpnPlayerImage.FM_YM2608, OpnPlayerImage.CHANNELS_YM2608);

    /** Stereo pans (FM `$B4`, rhythm and ADPCM-B), the LFO (AMS/FMS, operator AM), rhythm and ADPCM-B: the YM2608's. */
    val opna: Boolean get() = this == YM2608

    /** The first SSG channel. */
    val ssgFirst: Int get() = fmChannels

    /** The first rhythm channel, or -1. */
    val rhythmFirst: Int get() = if (opna) OpnPlayerImage.OPNA_CH_RHYTHM else -1

    /** The ADPCM-B channel, or -1. */
    val adpcmChannel: Int get() = if (opna) OpnPlayerImage.OPNA_CH_ADPCM else -1

    /** Bytes of this chip's lowered images. */
    val imageSize: Int get() = OpnPlayerImage.imageSize(code)

    /** What [channel] is; throws for a channel the chip does not have. */
    fun part(channel: Int): OpnPart = when {
        channel !in 0 until channelCount -> throw IllegalArgumentException("$label has no channel $channel")
        channel < fmChannels -> OpnPart.FM
        channel < fmChannels + OpnPlayerImage.SSG -> OpnPart.SSG
        channel < OpnPlayerImage.OPNA_CH_ADPCM -> OpnPart.RHYTHM
        else -> OpnPart.ADPCM
    }

    /** Channel names: "FM 1".., "SSG 1".., then the drums and "ADPCM". */
    val channelNames: List<String>
        get() = (1..fmChannels).map { "FM $it" } + (1..OpnPlayerImage.SSG).map { "SSG $it" } +
            (if (opna) RHYTHM_NAMES + "ADPCM" else emptyList())

    /** The parts this chip's sounds may be. */
    val parts: List<OpnPart> get() = if (opna) OpnPart.entries else listOf(OpnPart.FM, OpnPart.SSG)

    companion object {
        /** The six rhythm drums in register `$10` bit order. */
        val RHYTHM_NAMES = listOf("Kick", "Snare", "Cymbal", "Hi-hat", "Tom", "Rim")
    }
}

/** FM 3's mode (register `$27` bits 7-6): normal, special (each operator its own frequency) or CSM (timer A keys it). */
@Serializable
enum class OpnFm3Mode(val code: Int, val label: String) {
    @SerialName("normal") NORMAL(OpnPlayerImage.FM3_NORMAL, "Normal"),
    @SerialName("special") SPECIAL(OpnPlayerImage.FM3_SPECIAL, "Special: operator pitches"),
    @SerialName("csm") CSM(OpnPlayerImage.FM3_CSM, "CSM: timer A keys FM 3"),
}

/**
 * A sound of an OPN-family song's one slot list: an FM voice ([OpnFmSound]), an SSG sound ([OpnSsgSound]) or, on the
 * YM2608, a rhythm drum sound ([OpnRhythmSound]) or an ADPCM-B sample sound ([OpnAdpcmSound]). A slot's sound plays
 * on channels of its [part] only. Stored with a `"type"` of `"fm"`, `"ssg"`, `"rhythm"` or `"adpcm"`.
 */
@Serializable
sealed interface OpnInstrument {
    val name: String
    val part: OpnPart

    /** This sound renamed. */
    fun named(name: String): OpnInstrument
}

/**
 * An FM voice: the YM2612-compatible OPN voice (algorithm 0..7, operator 1's feedback 0..7, four [operators] numbered
 * as in the algorithm diagrams; [ams] 0..3 / [fms] 0..7 and each operator's AM bit are the YM2608's LFO depths and
 * must stay 0 on the YM2203), the drum [sweep], and [ch3]: four per-operator pitch offsets in 1/64 semitones
 * (−3072..3072, ±4 octaves) that FM 3 plays when the song's [OpnSound.fm3Mode] is special or CSM. [asYm] / [withYm]
 * let the Mega Drive's curated controls ([org.thortracker.system.ym2612.YmCurated]) and presets edit it.
 */
@Serializable
@SerialName("fm")
data class OpnFmSound(
    override val name: String = "",
    val algorithm: Int = 0,
    val feedback: Int = 0,
    val ams: Int = 0,
    val fms: Int = 0,
    val operators: List<YmOperator> = List(4) { YmOperator() },
    val ch3: List<Int> = listOf(0, 0, 0, 0),
    val sweep: YmSweep = YmSweep.NONE,
) : OpnInstrument {
    override val part: OpnPart get() = OpnPart.FM

    override fun named(name: String): OpnFmSound = copy(name = name)

    /** True when operator [op] (0-based) is a carrier under the algorithm. */
    fun isCarrier(op: Int): Boolean = OpnPlayerImage.CARRIERS[algorithm.coerceIn(0, 7)] shr op and 1 != 0

    /** The same voice as a Mega Drive FM instrument (for the shared curated controls, presets and painter). */
    fun asYm(): YmFmInstrument = YmFmInstrument(name, algorithm, feedback, ams, fms, operators, sweep)

    /** This voice with [ym]'s registers, name and sweep ([ch3] kept). */
    fun withYm(ym: YmFmInstrument): OpnFmSound =
        copy(name = ym.name, algorithm = ym.algorithm, feedback = ym.feedback, ams = ym.ams, fms = ym.fms, operators = ym.operators, sweep = ym.sweep)

    companion object {
        /** A voice from a Mega Drive FM instrument, without operator pitch offsets. */
        fun of(ym: YmFmInstrument): OpnFmSound = OpnFmSound().withYm(ym)
    }
}

/**
 * An SSG sound: FamiStudio-style software envelopes, one value per tick — [volume] 0..15 (empty = 15; its release
 * index is where a released note continues), [arpeggio] −64..63 semitones (empty = 0), [pitch] −64..63 sixteenths of a
 * semitone (absolute per tick, or accumulated each tick when [pitchRelative]; empty = 0), [mixer] per tick 0 tone and
 * noise, 1 noise, 2 tone, 3 neither (empty = tone) and [noise] the shared noise generator's period 0..31 per tick
 * (empty = untouched) — the hardware envelope [envelope] (the shared generator: a shape plays the generator's level
 * instead of the volume envelope's, which then only gates it; auto period = the note's tone period shifted right by the
 * octave) and the drum [sweep]. Envelope loop/release semantics are [NesEnvelope]'s.
 */
@Serializable
@SerialName("ssg")
data class OpnSsgSound(
    override val name: String = "",
    val volume: NesEnvelope = NesEnvelope(),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val mixer: NesEnvelope = NesEnvelope(),
    val noise: NesEnvelope = NesEnvelope(),
    val envelope: S5bEnvelope = S5bEnvelope.EPSM_DEFAULT,
    val sweep: YmSweep = YmSweep.NONE,
) : OpnInstrument {
    override val part: OpnPart get() = OpnPart.SSG

    override fun named(name: String): OpnSsgSound = copy(name = name)
}

/**
 * A YM2608 rhythm sound: a note on a rhythm channel plays that channel's drum from the rhythm ROM at [level] 0..31
 * (register `$18`-`$1D`, 1.5 dB steps, scaled by the channel volume `Cxx`) with the [left]/[right] outputs.
 */
@Serializable
@SerialName("rhythm")
data class OpnRhythmSound(
    override val name: String = "",
    val level: Int = 31,
    val left: Boolean = true,
    val right: Boolean = true,
) : OpnInstrument {
    override val part: OpnPart get() = OpnPart.RHYTHM

    override fun named(name: String): OpnRhythmSound = copy(name = name)
}

/**
 * A YM2608 ADPCM-B sound: plays song sample [sample] (an index into [OpnSound.samples]) at its recorded rate on note
 * [root] (absolute, 0..95) and transposed by the note's distance from it (delta-N), at [level] 0..255 (`$10B`, scaled
 * by `Cxx`) with the [left]/[right] outputs, once or repeating from its start while held ([loop]); the [sweep] bends
 * the pitch as on FM and SSG.
 */
@Serializable
@SerialName("adpcm")
data class OpnAdpcmSound(
    override val name: String = "",
    val sample: Int = 0,
    val root: Int = 48,
    val level: Int = 255,
    val left: Boolean = true,
    val right: Boolean = true,
    val loop: Boolean = false,
    val sweep: YmSweep = YmSweep.NONE,
) : OpnInstrument {
    override val part: OpnPart get() = OpnPart.ADPCM

    override fun named(name: String): OpnAdpcmSound = copy(name = name)
}

/**
 * Yamaha ADPCM-B data as the chip reads it: 4-bit codes, two per byte, the high nibble first (ymfm's decoder). Immutable;
 * equality is by content. Stored in project files as one Base64 string.
 */
@Serializable(with = AdpcmBytesSerializer::class)
class AdpcmBytes(bytes: ByteArray) {
    private val data: ByteArray = bytes.copyOf()

    val size: Int get() = data.size

    /** Byte [index] as 0..255. */
    operator fun get(index: Int): Int = data[index].toInt() and 0xFF

    /** A copy of the bytes. */
    fun toByteArray(): ByteArray = data.copyOf()

    /** Copies the bytes into [dest] at [offset]. */
    fun copyInto(dest: ByteArray, offset: Int) {
        data.copyInto(dest, offset)
    }

    override fun equals(other: Any?): Boolean = other is AdpcmBytes && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "AdpcmBytes(${data.size})"
}

@OptIn(ExperimentalEncodingApi::class)
object AdpcmBytesSerializer : KSerializer<AdpcmBytes> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("org.thortracker.system.opn.AdpcmBytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AdpcmBytes) = encoder.encodeString(Base64.encode(value.toByteArray()))

    override fun deserialize(decoder: Decoder): AdpcmBytes = try {
        AdpcmBytes(Base64.decode(decoder.decodeString()))
    } catch (e: IllegalArgumentException) {
        throw kotlinx.serialization.SerializationException("ADPCM sample data is not valid Base64: ${e.message}")
    }
}

/**
 * A YM2608 ADPCM-B sample, the song's own data (user-imported or authored, never bundled firmware): [name], the rate it
 * was recorded at ([rate] Hz, 1000..55466: the pitch it plays at its sound's root note) and its ADPCM-B [data]. The
 * lowering places every sample of the song in the chip's 256 KiB memory, each padded to a multiple of 32 bytes.
 */
@Serializable
data class OpnaSample(val name: String, val rate: Int, val data: AdpcmBytes) {
    /** Bytes the sample takes in the chip's memory (32-byte units). */
    val stored: Int get() = (data.size + OpnPlayerImage.ADPCM_ALIGN - 1) / OpnPlayerImage.ADPCM_ALIGN * OpnPlayerImage.ADPCM_ALIGN

    /** Length in seconds at its recorded rate (two codes per byte). */
    val seconds: Double get() = data.size * 2.0 / rate

    companion object {
        const val MAX_NAME = 16
    }
}

/**
 * An OPN-family song's chip data: the [chip]; [instruments] = exactly [SLOTS] slots (slot n = index n − 1; null = empty,
 * not playable) holding sounds of any part; the YM2608's ADPCM-B [samples] (at most [MAX_SAMPLES], together at most
 * 256 KiB); the tick rate [timerB] ([OpnPlayerImage.TIMER_B_MIN]..[OpnPlayerImage.TIMER_B_MAX]: 16 × (256 − TB) native
 * samples per tick); FM 3's [fm3Mode] and the CSM rate [timerA] (0..1023: timer A keys FM 3 every (1024 − NA) × 18 µs);
 * the YM2608's [lfo] (`$22`: 0 off, `$08`-`$0F` on at rate 0-7) and rhythm total level [rhythmLevel] (`$11`, 0..63).
 */
@Serializable
data class OpnSound(
    val chip: OpnChip,
    val instruments: List<OpnInstrument?>,
    val samples: List<OpnaSample> = emptyList(),
    val timerB: Int = OpnPlayerImage.TIMER_B_DEFAULT,
    val fm3Mode: OpnFm3Mode = OpnFm3Mode.NORMAL,
    val timerA: Int = 0,
    val lfo: Int = 0,
    val rhythmLevel: Int = 63,
) : SystemSound {
    override val system: SoundSystem get() = if (chip == OpnChip.YM2608) OpnaSystem else OpnSystem

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): OpnInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: OpnInstrument?): OpnSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Sample [index] replaced (or appended when [index] is [samples]' size). */
    fun withSample(index: Int, sample: OpnaSample): OpnSound =
        copy(samples = samples.toMutableList().also { if (index == it.size) it += sample else it[index] = sample })

    /** Bytes every sample takes in the chip's ADPCM-B memory. */
    val adpcmUsed: Int get() = samples.sumOf { it.stored }

    companion object {
        const val SLOTS = OpnPlayerImage.MAX_INSTRUMENTS
        const val MAX_SAMPLES = OpnPlayerImage.MAX_SAMPLES
    }
}

/** The chip data of an OPN-family song. */
val Song.opn: OpnSound get() = sound as OpnSound
