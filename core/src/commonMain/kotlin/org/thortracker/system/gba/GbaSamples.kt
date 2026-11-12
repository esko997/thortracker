package org.thortracker.system.gba

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Original procedural sounds and offline PCM edits. Every result is suitable for an undoable model replacement. */
object GbaSamples {
    val KINDS = listOf("Pulse", "Bass", "Bell", "Kick", "Snare", "Hat")
    const val MAX_WAV_BYTES = 16 * 1024 * 1024

    fun generate(kind: String, rate: Int = 8363): GbaSample {
        require(kind in KINDS) { "Unknown starter sample: $kind" }
        require(rate in 1000..GbaSample.MAX_RATE) { "Generated sample rate must be 1000–48000 Hz" }
        val pitched = kind in listOf("Pulse", "Bass", "Bell")
        val hz = 440.0 * 2.0.pow((48 - 57) / 12.0)
        val periodic = kind == "Pulse"
        val length = if (periodic) (rate / hz).roundToInt().coerceAtLeast(4) else (rate * if (kind == "Hat") 0.08 else 0.4).roundToInt()
        var noise = 0x62A93
        val pcm = List(length) { i ->
            noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
            val n = (noise ushr 8) / 8388607.5 - 1.0
            val t = i.toDouble() / rate
            val phase = 2.0 * PI * hz * t
            val value = when (kind) {
                "Pulse" -> if (i < length / 4) 0.7 else -0.23
                "Bass" -> (sin(phase) + 0.35 * sin(phase * 2) + 0.15 * sin(phase * 3)) * exp(-t * 8) * 0.65
                "Bell" -> (sin(phase) + 0.4 * sin(phase * 2.76) * exp(-t * 8)) * exp(-t * 5) * 0.65
                "Kick" -> sin(2.0 * PI * (48 * t + 12 * (1 - exp(-t * 18)))) * exp(-t * 13) * 0.9
                "Snare" -> (n * 0.65 + sin(2.0 * PI * 175 * t) * 0.25) * exp(-t * 18)
                else -> n * exp(-t * 65) * 0.7
            }
            (value * 127).roundToInt().coerceIn(-128, 127)
        }
        // A whole-period loop's physical fundamental follows its integer frame count.
        val tuning = if (periodic) (128 * 12 * kotlin.math.log2(hz * length / rate)).roundToInt() else 0
        return GbaSample(kind, pcm, rate, 48, tuning.coerceIn(-128, 127),
            volume = if (pitched) 44 else 54, loopEnd = if (periodic) length else 0)
    }

    fun starterBank(): GbaSound {
        val samples = KINDS.map { generate(it) }
        val release = GbaEnvelope(listOf(GbaEnvelopePoint(0, 64), GbaEnvelopePoint(8, 64), GbaEnvelopePoint(20, 0)), true, sustain = 1)
        return GbaSound(
            instruments = List(GbaSound.SLOTS) { i ->
                if (i < samples.size) GbaInstrument(samples[i].name, List(96) { i },
                    volumeEnvelope = if (i == 0) release else GbaEnvelope(), fadeout = if (i == 0) 1024 else 0)
                else GbaInstrument()
            }, samples = samples,
        )
    }

    /** RIFF/WAVE PCM 8/16/24/32-bit or IEEE float 32/64-bit, mono/stereo mixed to mono. Never silently truncates. */
    fun fromWav(bytes: ByteArray, name: String = "Imported", rootNote: Int = 48): GbaSample {
        require(bytes.size in 12..MAX_WAV_BYTES) { "WAV must be 12 bytes–16 MiB; shorten the source first" }
        require(rootNote in 0..95) { "Root note must be C0–B7" }
        fun u8(p: Int) = bytes[p].toInt() and 255
        fun u16(p: Int) = u8(p) or (u8(p + 1) shl 8)
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        fun tag(p: Int, value: String) = value.indices.all { u8(p + it) == value[it].code }
        require(tag(0, "RIFF") && tag(8, "WAVE")) { "Import an uncompressed RIFF WAVE file" }
        val endLong = u32(4) + 8
        require(endLong in 12..bytes.size.toLong()) { "WAV RIFF length is truncated or invalid" }
        val end = endLong.toInt()
        var p = 12
        var format = -1; var data = -1; var dataSize = 0; var formatSize = 0
        while (p < end) {
            require(end - p >= 8) { "Truncated WAV chunk header" }
            val size = u32(p + 4)
            require(size <= end - p - 8) { "Truncated WAV chunk" }
            if (tag(p, "fmt ")) { require(format < 0) { "WAV has duplicate format chunks" }; format = p + 8; formatSize = size.toInt() }
            if (tag(p, "data")) { require(data < 0) { "WAV has duplicate data chunks" }; data = p + 8; dataSize = size.toInt() }
            val next = p.toLong() + 8 + size + (size and 1)
            require(next <= end) { "WAV chunk is missing its padding byte" }
            p = next.toInt()
        }
        require(format >= 0 && formatSize >= 16 && data >= 0) { "WAV needs format and data chunks" }
        val encoding = u16(format); val channels = u16(format + 2)
        val rate = u32(format + 4); val align = u16(format + 12); val bits = u16(format + 14)
        require(channels in 1..2) { "WAV must be mono or stereo" }
        require(rate in 1L..GbaSample.MAX_RATE.toLong()) { "WAV rate must be 1–48000 Hz; resample the source first" }
        require((encoding == 1 && bits in listOf(8, 16, 24, 32)) || (encoding == 3 && bits in listOf(32, 64))) {
            "WAV must contain PCM 8/16/24/32-bit or float 32/64-bit samples"
        }
        val width = bits / 8
        require(align == width * channels && dataSize % align == 0) { "WAV frame alignment is invalid" }
        val count = dataSize / align
        require(count in 1..GbaSound.MAX_PCM_BYTES) { "Sample exceeds the 512 KiB PCM budget; trim or resample before import" }
        fun value(at: Int): Double = if (encoding == 3) {
            val raw = if (bits == 32) Float.fromBits(u32(at).toInt()).toDouble()
            else Double.fromBits(u32(at) or (u32(at + 4) shl 32))
            require(raw.isFinite()) { "WAV contains non-finite floating-point samples" }
            raw.coerceIn(-1.0, 1.0)
        } else when (bits) {
            8 -> (u8(at) - 128) / 128.0
            16 -> u16(at).toShort() / 32768.0
            24 -> ((u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)) shl 8 shr 8) / 8388608.0
            else -> u32(at).toInt() / 2147483648.0
        }
        val pcm = List(count) { i ->
            var sum = 0.0
            for (ch in 0 until channels) sum += value(data + i * align + ch * width)
            (sum * 128 / channels).roundToInt().coerceIn(-128, 127)
        }
        return GbaSample(name, pcm, rate.toInt(), rootNote)
    }

    private fun checked(sample: GbaSample) {
        GbaRules.sampleProblem(sample)?.let { throw IllegalArgumentException(it) }
    }

    fun trim(sample: GbaSample, start: Int, end: Int): GbaSample {
        checked(sample)
        require(start >= 0 && end <= sample.pcm.size && start < end) { "Trim needs a nonempty range inside the sample" }
        val ls = maxOf(sample.loopStart, start) - start
        val le = minOf(sample.loopEnd, end) - start
        val loop = sample.loopEnd > sample.loopStart && le > ls
        return sample.copy(pcm = sample.pcm.subList(start, end).toList(), loopStart = if (loop) ls else 0, loopEnd = if (loop) le else 0)
    }

    fun gain(sample: GbaSample, factor: Double): GbaSample {
        checked(sample)
        require(factor.isFinite() && factor in 0.0..16.0) { "Gain must be between 0 and 16 times" }
        return sample.copy(pcm = sample.pcm.map { (it * factor).roundToInt().coerceIn(-128, 127) })
    }

    fun normalise(sample: GbaSample): GbaSample {
        checked(sample)
        val positive = sample.pcm.maxOrNull() ?: 0
        val negative = abs(sample.pcm.minOrNull() ?: 0)
        val factor = minOf(if (positive > 0) 127.0 / positive else Double.POSITIVE_INFINITY,
            if (negative > 0) 128.0 / negative else Double.POSITIVE_INFINITY)
        if (!factor.isFinite()) return sample
        return sample.copy(pcm = sample.pcm.map { (it * factor).roundToInt().coerceIn(-128, 127) })
    }

    fun fade(sample: GbaSample, fadeIn: Int, fadeOut: Int): GbaSample {
        checked(sample)
        require(fadeIn in 0..sample.pcm.size && fadeOut in 0..sample.pcm.size) { "Fade lengths must fit inside the sample" }
        return sample.copy(pcm = sample.pcm.mapIndexed { i, value ->
            val attack = if (fadeIn == 0 || i >= fadeIn) 1.0 else if (fadeIn == 1) 0.0 else i.toDouble() / (fadeIn - 1)
            val remaining = sample.pcm.lastIndex - i
            val release = if (fadeOut == 0 || remaining >= fadeOut) 1.0 else if (fadeOut == 1) 0.0 else remaining.toDouble() / (fadeOut - 1)
            (value * attack * release).roundToInt()
        })
    }

    /** Linear interpolation, with frame-count and loop-edge rounding exposed by the resulting model. */
    fun resample(sample: GbaSample, rate: Int): GbaSample {
        checked(sample)
        require(rate in 1..GbaSample.MAX_RATE) { "Sample rate must be 1–48000 Hz" }
        val size = (sample.pcm.size.toLong() * rate + sample.sampleRate / 2) / sample.sampleRate
        require(size in 1L..GbaSound.MAX_PCM_BYTES.toLong()) { "Resampled PCM must fit the 512 KiB budget and contain at least one frame" }
        if (rate == sample.sampleRate) return sample
        val pcm = List(size.toInt()) { i ->
            val position = i.toDouble() * sample.sampleRate / rate
            val a = position.toInt().coerceAtMost(sample.pcm.lastIndex)
            val b = (a + 1).coerceAtMost(sample.pcm.lastIndex)
            (sample.pcm[a] + (sample.pcm[b] - sample.pcm[a]) * (position - a)).roundToInt().coerceIn(-128, 127)
        }
        fun edge(value: Int) = ((value.toLong() * rate + sample.sampleRate / 2) / sample.sampleRate).toInt().coerceIn(0, pcm.size)
        val ls = edge(sample.loopStart); val le = edge(sample.loopEnd)
        val loop = sample.loopEnd > sample.loopStart && le > ls
        return sample.copy(pcm = pcm, sampleRate = rate, loopStart = if (loop) ls else 0, loopEnd = if (loop) le else 0)
    }
}
