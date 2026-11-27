package org.thortracker.system.studio

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeCollection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Authored cycles only. A point edit shares the three untouched immutable rows. */
@Serializable(with = StudioWavesSerializer::class)
class StudioWaves private constructor(private val rows: Array<ByteArray>) {
    fun point(wave: Int, point: Int): Int = rows[wave][point].toInt()

    fun withPoint(wave: Int, point: Int, value: Int): StudioWaves =
        withStroke(wave, point, value, point, value)

    fun withStroke(wave: Int, fromPoint: Int, fromValue: Int, toPoint: Int, toValue: Int): StudioWaves {
        require(wave in 0 until COUNT && fromPoint in 0 until POINTS && toPoint in 0 until POINTS)
        require(fromValue in -128..127 && toValue in -128..127)
        val source = rows[wave]
        var edited: ByteArray? = null
        // Evaluate from the left endpoint in either direction so the same segment is byte-identical.
        val left = minOf(fromPoint, toPoint)
        val right = maxOf(fromPoint, toPoint)
        val start = if (fromPoint < toPoint) fromValue else toValue
        val end = if (fromPoint < toPoint) toValue else fromValue
        for (i in left..right) {
            val v = if (left == right) toValue else start + ((end - start) * (i - left).toDouble() / (right - left)).roundToInt()
            if (source[i].toInt() != v) {
                if (edited == null) edited = source.copyOf()
                edited[i] = v.toByte()
            }
        }
        val changed = edited ?: return this
        return StudioWaves(rows.copyOf().also { it[wave] = changed })
    }

    internal fun writeTo(bytes: ByteArray, offset: Int) {
        for (i in rows.indices) rows[i].copyInto(bytes, offset + POINTS * i)
    }
    override fun equals(other: Any?): Boolean = this === other || other is StudioWaves &&
        rows.indices.all { rows[it] === other.rows[it] || rows[it].contentEquals(other.rows[it]) }
    override fun hashCode(): Int = rows.fold(1) { h, row -> 31 * h + row.contentHashCode() }

    companion object {
        const val COUNT = 4
        const val POINTS = 64
        private val initial = StudioWaves(Array(COUNT) { w -> ByteArray(POINTS) { i ->
            when (w) {
                0 -> (127 * sin(2 * PI * i / POINTS)).roundToInt()
                1 -> 127 - 8 * abs((i + 16) % POINTS - 32)
                2 -> 4 * i - 128
                else -> if (i < 32) 127 else -128
            }.coerceIn(-128, 127).toByte()
        } })
        fun initial(): StudioWaves = initial
        fun of(waves: List<List<Int>>): StudioWaves {
            require(waves.size == COUNT && waves.all { it.size == POINTS }) { "Wavetable needs four cycles of 64 points" }
            return StudioWaves(Array(COUNT) { w -> ByteArray(POINTS) { i ->
                waves[w][i].also { require(it in -128..127) { "Wavetable points must be −128 to 127" } }.toByte()
            } })
        }
        internal fun decode(decoder: Decoder): StudioWaves {
            val rows = Array(COUNT) { ByteArray(POINTS) }
            decoder.readFixed(StudioWavesSerializer.descriptor, COUNT) { i ->
                // The strategy fills our private destination and returns Unit, never an array.
                decodeSerializableElement(StudioWavesSerializer.descriptor, i, ReadCycle(rows[i]))
            }
            return StudioWaves(rows)
        }
    }
}

private val cycleDescriptor = ListSerializer(Int.serializer()).descriptor

/** Refuse overlong input before decoding the excess points, including in an untrusted manifest. */
@OptIn(ExperimentalSerializationApi::class)
private inline fun Decoder.readFixed(desc: SerialDescriptor, size: Int, read: CompositeDecoder.(Int) -> Unit) =
    decodeStructure(desc) {
        if (decodeSequentially()) {
            require(decodeCollectionSize(desc) == size) { "Expected $size wavetable elements" }
            for (i in 0 until size) read(i)
        } else {
            var count = 0
            while (true) {
                val i = decodeElementIndex(desc)
                if (i == CompositeDecoder.DECODE_DONE) break
                require(i == count && count < size) { "Expected $size wavetable elements" }
                read(count++)
            }
            require(count == size) { "Expected $size wavetable elements" }
        }
    }

private class ReadCycle(private val destination: ByteArray) : DeserializationStrategy<Unit> {
    override val descriptor = cycleDescriptor
    override fun deserialize(decoder: Decoder) = decoder.readFixed(descriptor, StudioWaves.POINTS) { i ->
        val value = decodeIntElement(descriptor, i)
        require(value in -128..127) { "Wavetable points must be −128 to 127" }
        destination[i] = value.toByte()
    }
}

private class WriteCycle(private val wave: Int) : SerializationStrategy<StudioWaves> {
    override val descriptor = cycleDescriptor
    override fun serialize(encoder: Encoder, value: StudioWaves) = encoder.encodeCollection(descriptor, StudioWaves.POINTS) {
        for (p in 0 until StudioWaves.POINTS) encodeIntElement(descriptor, p, value.point(wave, p))
    }
}

object StudioWavesSerializer : KSerializer<StudioWaves> {
    override val descriptor = ListSerializer(ListSerializer(Int.serializer())).descriptor
    private val writers = Array(StudioWaves.COUNT) { WriteCycle(it) }
    override fun serialize(encoder: Encoder, value: StudioWaves) = encoder.encodeCollection(descriptor, StudioWaves.COUNT) {
        for (w in writers.indices) encodeSerializableElement(descriptor, w, writers[w], value)
    }
    override fun deserialize(decoder: Decoder): StudioWaves = StudioWaves.decode(decoder)
}
