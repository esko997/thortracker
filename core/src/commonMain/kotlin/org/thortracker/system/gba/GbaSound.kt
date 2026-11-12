package org.thortracker.system.gba

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encoding.Encoder
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/** Embedded, signed eight-bit PCM; loop end is exclusive. No external sample paths. */
@Serializable
data class GbaSample(
    val name: String = "Sample",
    @Serializable(with = GbaPcmSerializer::class) val pcm: List<Int> = emptyList(),
    val sampleRate: Int = 8363,
    val rootNote: Int = 48,
    val fineTune: Int = 0,
    val volume: Int = 64,
    val pan: Int = 128,
    val loopStart: Int = 0,
    val loopEnd: Int = 0,
) {
    companion object { const val MAX_RATE = 48000 }
}

@Serializable
data class GbaEnvelopePoint(val tick: Int, val value: Int)

@Serializable
data class GbaEnvelope(
    @Serializable(with = GbaPointsSerializer::class)
    val points: List<GbaEnvelopePoint> = listOf(GbaEnvelopePoint(0, 64), GbaEnvelopePoint(12, 0)),
    val enabled: Boolean = false,
    val sustain: Int = -1,
    val loopStart: Int = -1,
    val loopEnd: Int = -1,
)

@Serializable
data class GbaInstrument(
    val name: String = "Empty",
    @Serializable(with = GbaKeymapSerializer::class) val keymap: List<Int> = List(96) { -1 },
    val volumeEnvelope: GbaEnvelope = GbaEnvelope(),
    val panEnvelope: GbaEnvelope = GbaEnvelope(),
    val fadeout: Int = 0,
)

@Serializable
data class GbaSound(
    @Serializable(with = GbaInstrumentsSerializer::class)
    val instruments: List<GbaInstrument> = List(SLOTS) { GbaInstrument() },
    @Serializable(with = GbaSamplesSerializer::class) val samples: List<GbaSample> = emptyList(),
    val bpm: Int = 125,
) : SystemSound {
    override val system: SoundSystem get() = GbaSystem
    fun instrument(slot: Int): GbaInstrument? = if (slot in 1..SLOTS) instruments.getOrNull(slot - 1) else null
    companion object {
        const val SLOTS = 64
        const val MAX_SAMPLES = 128
        /** Product limit on embedded PCM, not a claim about the GBA's RAM. */
        const val MAX_PCM_BYTES = 512 * 1024
    }
}

val Song.gba: GbaSound get() = sound as GbaSound

/** Reject untrusted collection lengths before allocating their typed backing storage. */
@OptIn(ExperimentalSerializationApi::class)
abstract class GbaBoundedListSerializer<T>(
    private val item: KSerializer<T>,
    private val limit: Int,
    private val label: String,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(item)
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: List<T>) {
        if (value.size > limit) throw SerializationException("$label holds at most $limit entries")
        delegate.serialize(encoder, value)
    }
    override fun deserialize(decoder: Decoder): List<T> {
        val input = decoder.beginStructure(descriptor)
        val size = input.decodeCollectionSize(descriptor)
        if (size > limit) throw SerializationException("$label holds at most $limit entries")
        val result = ArrayList<T>(if (size >= 0) size else minOf(limit, 16))
        if (input.decodeSequentially()) {
            if (size < 0) throw SerializationException("$label has no collection length")
            repeat(size) { result.add(input.decodeSerializableElement(descriptor, it, item)) }
        } else {
            while (true) {
                val index = input.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index != result.size || index >= limit) throw SerializationException("$label holds at most $limit entries")
                result.add(input.decodeSerializableElement(descriptor, index, item))
            }
        }
        input.endStructure(descriptor)
        return result
    }
}

object GbaPcmSerializer : GbaBoundedListSerializer<Int>(Int.serializer(), GbaSound.MAX_PCM_BYTES, "Sample PCM")
object GbaKeymapSerializer : GbaBoundedListSerializer<Int>(Int.serializer(), 96, "Instrument keymap")
object GbaPointsSerializer : GbaBoundedListSerializer<GbaEnvelopePoint>(GbaEnvelopePoint.serializer(), 12, "Envelope")
object GbaSamplesSerializer : GbaBoundedListSerializer<GbaSample>(GbaSample.serializer(), GbaSound.MAX_SAMPLES, "Sample bank") {
    override fun deserialize(decoder: Decoder): List<GbaSample> {
        if (decoder !is JsonDecoder) {
            val samples = super.deserialize(decoder)
            if (samples.sumOf { it.pcm.size.toLong() } > GbaSound.MAX_PCM_BYTES) {
                throw SerializationException("Embedded PCM exceeds the 512 KiB backend budget")
            }
            return samples
        }
        val tree = decoder.decodeJsonElement()
        val array = tree as? JsonArray ?: throw SerializationException("Sample bank must be an array")
        if (array.size > GbaSound.MAX_SAMPLES) throw SerializationException("Sample bank holds at most 128 entries")
        var total = 0L
        for (entry in array) {
            val sample = entry as? JsonObject ?: throw SerializationException("Sample must be an object")
            val pcm = sample["pcm"]
            if (pcm != null && pcm !is JsonArray) throw SerializationException("Sample PCM must be an array")
            total += (pcm as? JsonArray)?.size ?: 0
            if (total > GbaSound.MAX_PCM_BYTES) throw SerializationException("Embedded PCM exceeds the 512 KiB backend budget")
        }
        return decoder.json.decodeFromJsonElement(ListSerializer(GbaSample.serializer()), tree)
    }
}
object GbaInstrumentsSerializer : GbaBoundedListSerializer<GbaInstrument>(GbaInstrument.serializer(), GbaSound.SLOTS, "Instrument bank")
