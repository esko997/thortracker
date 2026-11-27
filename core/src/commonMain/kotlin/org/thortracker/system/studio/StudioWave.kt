package org.thortracker.system.studio

import org.thortracker.audio.WaveChunks
import org.thortracker.audio.i32le
import org.thortracker.audio.u16le
import org.thortracker.audio.u32le
import org.thortracker.audio.u8

/** Decodes WAV into the transport's Float32 format without mixing channels, resampling or quantizing. */
object StudioWave {
    const val MAX_FILE_BYTES = StudioPcm.MAX_BYTES + 4 * 1024 * 1024
    private const val PCM = 1
    private const val FLOAT = 3
    private const val EXTENSIBLE = 0xfffe
    private val subtypeTail = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)

    fun decode(bytes: ByteArray): StudioPcm {
        require(bytes.size <= MAX_FILE_BYTES) { "WAV file exceeds the 20 MiB input limit" }
        val wave = WaveChunks.parse(bytes)
        val fmt = wave.fmt
        val channels = bytes.u16le(fmt + 2)
        val rate = bytes.u32le(fmt + 4)
        val align = bytes.u16le(fmt + 12)
        val bits = bytes.u16le(fmt + 14)
        var encoding = bytes.u16le(fmt)
        var precision = bits
        require(channels in 1..2) { "Studio WAV must be mono or stereo" }
        require(rate in StudioPcm.MIN_SAMPLE_RATE.toLong()..StudioPcm.MAX_SAMPLE_RATE.toLong()) {
            "Studio WAV rate must be 8000–192000 Hz"
        }
        if (encoding == EXTENSIBLE) {
            require(wave.fmtBytes >= 40 && bytes.u16le(fmt + 16) in 22..(wave.fmtBytes - 18)) {
                "Truncated extensible WAV format"
            }
            precision = bytes.u16le(fmt + 18)
            val mask = bytes.i32le(fmt + 20)
            val expectedMask = if (channels == 1) 4 else 3
            require(mask == 0 || mask == expectedMask) { "Unsupported WAV speaker layout" }
            require(subtypeTail.indices.all { bytes[fmt + 28 + it] == subtypeTail[it] }) { "Unsupported extensible WAV encoding" }
            encoding = bytes.i32le(fmt + 24)
        }
        val pcm = encoding == PCM && when (bits) {
            8, 16, 24 -> precision == bits
            32 -> precision == 24 // 24 valid bits left-aligned in a 32-bit container.
            else -> false
        }
        val float = encoding == FLOAT && bits == 32 && precision == 32
        require(pcm || float) { "Studio WAV needs PCM 8/16/24-bit or IEEE float32; convert unsupported precision explicitly" }
        val width = bits / 8
        require(align == channels * width && wave.dataBytes % align == 0 && bytes.u32le(fmt + 8) == rate * align) {
            "WAV frame alignment or byte rate is invalid"
        }
        return StudioPcm.decode(channels, rate.toInt(), wave.dataBytes / width) { i ->
            val pos = wave.data + i * width
            if (float) Float.fromBits(bytes.i32le(pos)) else when (bits) {
                8 -> (bytes.u8(pos) - 128) / 128f
                16 -> bytes.u16le(pos).toShort() / 32768f
                24 -> ((bytes.u16le(pos) or (bytes.u8(pos + 2) shl 16)) shl 8 shr 8) / 8388608f
                else -> {
                    require(bytes.u8(pos) == 0) { "WAV contains nonzero bits in its declared padding" }
                    (bytes.i32le(pos) shr 8) / 8388608f
                }
            }
        }
    }
}
