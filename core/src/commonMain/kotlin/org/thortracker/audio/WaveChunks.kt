package org.thortracker.audio

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 255
internal fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.i32le(at: Int): Int = u16le(at) or (u16le(at + 2) shl 16)
internal fun ByteArray.u32le(at: Int): Long = i32le(at).toLong() and 0xffff_ffffL
internal fun ByteArray.matchesTag(at: Int, tag: String): Boolean {
    for (i in tag.indices) if (u8(at + i) != tag[i].code) return false
    return true
}

/** RIFF structure only; destination-specific formats, limits and conversions are deliberately separate. */
internal data class WaveChunks(val fmt: Int, val fmtBytes: Int, val data: Int, val dataBytes: Int) {
    companion object {
        fun parse(bytes: ByteArray): WaveChunks {
            require(bytes.size >= 12 && bytes.matchesTag(0, "RIFF") && bytes.matchesTag(8, "WAVE")) {
                "Import an uncompressed RIFF WAVE file"
            }
            val declaredEnd = bytes.u32le(4) + 8
            require(declaredEnd in 12..bytes.size.toLong()) { "WAV RIFF length is truncated or invalid" }
            val end = declaredEnd.toInt()
            var at = 12
            var fmt = -1
            var data = -1
            var fmtBytes = 0
            var dataBytes = 0
            while (at < end) {
                require(end - at >= 8) { "Truncated WAV chunk header" }
                val size = bytes.u32le(at + 4)
                require(size <= end - at - 8) { "Truncated WAV chunk" }
                if (bytes.matchesTag(at, "fmt ")) {
                    require(fmt < 0) { "WAV has duplicate format chunks" }
                    fmt = at + 8
                    fmtBytes = size.toInt()
                }
                if (bytes.matchesTag(at, "data")) {
                    require(data < 0) { "WAV has duplicate data chunks" }
                    data = at + 8
                    dataBytes = size.toInt()
                }
                val next = at.toLong() + 8 + size + (size and 1)
                require(next <= end) { "WAV chunk is missing its padding byte" }
                at = next.toInt()
            }
            require(fmt >= 0 && fmtBytes >= 16 && data >= 0) { "WAV needs format and data chunks" }
            return WaveChunks(fmt, fmtBytes, data, dataBytes)
        }
    }
}
