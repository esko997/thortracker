package org.thortracker.model

import org.thortracker.audio.i32le
import org.thortracker.audio.matchesTag
import org.thortracker.system.studio.StudioAssets
import org.thortracker.system.studio.StudioPcm

/** A validated save snapshot. Its sink must consume each chunk synchronously without modifying it. */
class ProjectDocument internal constructor(private val manifest: ByteArray, private val assets: StudioAssets) {
    val size: Int = manifest.size + if (assets.decodedBytes == 0) 0 else
        StudioBundle.HEADER_BYTES + assets.ids.size * StudioBundle.RECORD_BYTES + assets.decodedBytes + Int.SIZE_BYTES

    fun writeTo(sink: (bytes: ByteArray, count: Int) -> Unit) {
        if (assets.decodedBytes == 0) sink(manifest, manifest.size)
        else StudioBundle.write(manifest, assets, sink)
    }
}

/**
 * Studio bundle v1, all integers little-endian: magic, version, JSON byte count, asset count;
 * the UTF-8 ProjectCodec manifest; [asset ID, channels, rate, interleaved sample count, Float32 bits] records;
 * a CRC-32 of every preceding byte. No compression, paths, or external resources.
 */
internal object StudioBundle {
    private const val MAGIC = "THORBND\n"
    private const val VERSION = 1
    const val MAGIC_BYTES = 8
    const val HEADER_BYTES = MAGIC_BYTES + 3 * Int.SIZE_BYTES
    const val RECORD_BYTES = 4 * Int.SIZE_BYTES
    const val MAX_JSON_BYTES = 16 * 1024 * 1024
    private const val CHUNK_BYTES = 8192

    fun isBundle(prefix: ByteArray, used: Int): Boolean = used == MAGIC_BYTES && prefix.matchesTag(0, MAGIC)

    fun write(manifest: ByteArray, assets: StudioAssets, sink: (ByteArray, Int) -> Unit) {
        val checksum = Crc32()
        fun write(bytes: ByteArray, count: Int) {
            checksum.update(bytes, count)
            sink(bytes, count)
        }
        val ids = assets.ids.sorted()
        val buffer = ByteArray(CHUNK_BYTES)
        for (i in MAGIC.indices) buffer[i] = MAGIC[i].code.toByte()
        buffer.putInt(MAGIC_BYTES, VERSION)
        buffer.putInt(MAGIC_BYTES + 4, manifest.size)
        buffer.putInt(MAGIC_BYTES + 8, ids.size)
        write(buffer, HEADER_BYTES)
        // Use a reusable chunk for the manifest too; the sink never receives our private array.
        var at = 0
        while (at < manifest.size) {
            val count = minOf(buffer.size, manifest.size - at)
            manifest.copyInto(buffer, endIndex = at + count, startIndex = at)
            write(buffer, count)
            at += count
        }
        for (id in ids) {
            val pcm = checkNotNull(assets[id])
            buffer.putInt(0, id)
            buffer.putInt(4, pcm.channels)
            buffer.putInt(8, pcm.sampleRate)
            buffer.putInt(12, pcm.sampleCount)
            write(buffer, RECORD_BYTES)
            var used = 0
            for (i in 0 until pcm.sampleCount) {
                buffer.putInt(used, pcm.sample(i).toRawBits())
                used += Float.SIZE_BYTES
                if (used == buffer.size) {
                    write(buffer, used)
                    used = 0
                }
            }
            if (used != 0) write(buffer, used)
        }
        buffer.putInt(0, checksum.value)
        sink(buffer, Int.SIZE_BYTES)
    }

    fun read(prefix: ByteArray, source: ProjectSource): Pair<ByteArray, StudioAssets> {
        val checksum = Crc32()
        checksum.update(prefix, MAGIC_BYTES)
        val buffer = ByteArray(CHUNK_BYTES)
        fun read(bytes: ByteArray, count: Int) {
            source.exact(bytes, count)
            checksum.update(bytes, count)
        }
        read(buffer, HEADER_BYTES - MAGIC_BYTES)
        val version = buffer.i32le(0)
        if (version != VERSION) throw ProjectFormatException("Studio bundle version $version is unsupported; this version reads $VERSION")
        val manifestBytes = buffer.i32le(4)
        val assetCount = buffer.i32le(8)
        require(manifestBytes in 1..MAX_JSON_BYTES) { "Invalid Studio bundle manifest size" }
        require(assetCount in 1..StudioAssets.MAX_ASSETS) { "Invalid Studio bundle asset count" }
        val manifest = ByteArray(manifestBytes)
        read(manifest, manifest.size)
        val entries = mutableMapOf<Int, StudioPcm>()
        val unique = mutableSetOf<StudioPcm>()
        var decodedBytes = 0
        repeat(assetCount) {
            read(buffer, RECORD_BYTES)
            val id = buffer.i32le(0)
            val channels = buffer.i32le(4)
            val rate = buffer.i32le(8)
            val count = buffer.i32le(12)
            require(id in 1..StudioAssets.MAX_ASSETS && id !in entries) { "Invalid or duplicate Studio asset ID $id" }
            StudioPcm.validateShape(channels, rate, count)
            val bytes = count * Float.SIZE_BYTES
            require(bytes <= StudioAssets.MAX_BYTES - decodedBytes) { "Studio bundle exceeds the 32 MiB decoded asset budget" }
            decodedBytes += bytes
            var consumed = 0
            var used = 0
            var available = 0
            val asset = StudioPcm.decode(channels, rate, count) {
                if (used == available) {
                    available = minOf(buffer.size, bytes - consumed)
                    read(buffer, available)
                    consumed += available
                    used = 0
                }
                Float.fromBits(buffer.i32le(used)).also { used += Float.SIZE_BYTES }
            }
            require(unique.add(asset)) { "Duplicate PCM records in Studio bundle" }
            entries[id] = asset
        }
        source.exact(buffer, Int.SIZE_BYTES)
        require(buffer.i32le(0) == checksum.value) { "Studio bundle checksum does not match" }
        require(source.upTo(buffer, 1) == 0) { "Extra data after the Studio bundle" }
        return manifest to StudioAssets.fromEntries(entries)
    }

    private fun ByteArray.putInt(at: Int, value: Int) {
        this[at] = value.toByte()
        this[at + 1] = (value ushr 8).toByte()
        this[at + 2] = (value ushr 16).toByte()
        this[at + 3] = (value ushr 24).toByte()
    }

    private class Crc32 {
        private var state = -1
        val value: Int get() = state.inv()
        fun update(bytes: ByteArray, length: Int) {
            var crc = state
            for (i in 0 until length) crc = table[(crc xor bytes[i].toInt()) and 0xff] xor (crc ushr 8)
            state = crc
        }

        companion object {
            private val table = IntArray(256) { index ->
                var crc = index
                repeat(8) { crc = (crc ushr 1) xor (if ((crc and 1) != 0) 0xedb8_8320.toInt() else 0) }
                crc
            }
        }
    }
}

/** Reads strictly bounded data over JVM streams, native files and in-memory fixtures. */
internal class ProjectSource(private val read: (ByteArray, Int, Int) -> Int) {
    fun upTo(buffer: ByteArray, count: Int): Int {
        var at = 0
        while (at < count) {
            val n = read(buffer, at, count - at)
            if (n == -1) break
            require(n > 0 && n <= count - at) { "Project source returned an invalid read length" }
            at += n
        }
        return at
    }

    fun exact(buffer: ByteArray, count: Int) {
        require(upTo(buffer, count) == count) { "Truncated Studio bundle" }
    }

    fun json(prefix: ByteArray, count: Int): ByteArray {
        if (count < prefix.size) return prefix.copyOf(count)
        val chunks = ArrayList<ByteArray>()
        var size = count
        while (size < StudioBundle.MAX_JSON_BYTES) {
            val chunk = ByteArray(minOf(8192, StudioBundle.MAX_JSON_BYTES - size))
            val last = upTo(chunk, chunk.size)
            if (last > 0) chunks += chunk
            size += last
            if (last < chunk.size) break
        }
        if (size == StudioBundle.MAX_JSON_BYTES) require(upTo(ByteArray(1), 1) == 0) { "Project JSON exceeds the 16 MiB limit" }
        val bytes = prefix.copyOf(size)
        var at = count
        for (chunk in chunks) {
            val used = minOf(chunk.size, size - at)
            chunk.copyInto(bytes, at, endIndex = used)
            at += used
        }
        return bytes
    }
}
