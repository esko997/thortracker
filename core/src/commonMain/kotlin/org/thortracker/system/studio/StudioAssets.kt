package org.thortracker.system.studio

/** Immutable, normalized mono/stereo Float32, interleaved by frame at the original sample rate. */
class StudioPcm private constructor(val channels: Int, val sampleRate: Int, private val pcm: FloatArray) {
    val sampleCount: Int get() = pcm.size
    val frames: Int get() = pcm.size / channels
    val waveformBins: Int get() = overview.size / 2
    private val blockFrames = (frames + MAX_WAVE_BLOCKS - 1) / MAX_WAVE_BLOCKS
    private val blockCount = (frames + blockFrames - 1) / blockFrames
    private val leaves = if (blockCount == 1) 1 else (blockCount - 1).takeHighestOneBit() * 2
    private val lows = FloatArray(leaves * 2) { 1f }
    private val highs = FloatArray(leaves * 2) { -1f }
    private val overview = FloatArray(minOf(frames, 256) * 2)
    private val hash: Int

    init {
        // Build the immutable range index once, while importing or decoding the project.
        var h = (31 * channels + sampleRate) * 31 + pcm.size
        for (block in 0 until blockCount) {
            val start = block * blockFrames * channels
            val end = minOf((block + 1) * blockFrames, frames) * channels
            var low = 1f
            var high = -1f
            for (i in start until end) {
                val value = pcm[i]
                require(value >= -1f && value <= 1f) { "Studio PCM must be finite and within −1 to +1" }
                low = minOf(low, value)
                high = maxOf(high, value)
                h = 31 * h + value.toRawBits()
            }
            lows[leaves + block] = low
            highs[leaves + block] = high
        }
        for (node in leaves - 1 downTo 1) {
            lows[node] = minOf(lows[node * 2], lows[node * 2 + 1])
            highs[node] = maxOf(highs[node * 2], highs[node * 2 + 1])
        }
        for (bin in 0 until waveformBins) {
            val start = bin * frames / waveformBins
            val end = (bin + 1) * frames / waveformBins
            overview[2 * bin] = waveformMin(start, end)
            overview[2 * bin + 1] = waveformMax(start, end)
        }
        hash = h
    }

    fun sample(index: Int): Float = pcm[index]
    fun copySamplesTo(destination: FloatArray, destinationOffset: Int = 0) {
        pcm.copyInto(destination, destinationOffset)
    }

    fun waveformMin(bin: Int): Float = overview[2 * bin]
    fun waveformMax(bin: Int): Float = overview[2 * bin + 1]

    /** Exact min/max across both channels of [startFrame, endFrame). At most two partial blocks are scanned. */
    fun waveformMin(startFrame: Int, endFrame: Int): Float = extreme(startFrame, endFrame, lows, minimum = true)
    fun waveformMax(startFrame: Int, endFrame: Int): Float = extreme(startFrame, endFrame, highs, minimum = false)

    private fun extreme(startFrame: Int, endFrame: Int, tree: FloatArray, minimum: Boolean): Float {
        require(startFrame >= 0 && startFrame < endFrame && endFrame <= frames) { "Invalid Studio waveform range" }
        val seed = if (minimum) 1f else -1f
        val firstBlock = (startFrame + blockFrames - 1) / blockFrames
        val lastBlock = endFrame / blockFrames
        if (firstBlock > lastBlock) return edge(startFrame, endFrame, seed, minimum)
        var result = edge(startFrame, firstBlock * blockFrames, seed, minimum)
        result = edge(lastBlock * blockFrames, endFrame, result, minimum)
        var left = leaves + firstBlock
        var right = leaves + lastBlock
        while (left < right) {
            if ((left and 1) != 0) {
                val value = tree[left++]
                result = if (minimum) minOf(result, value) else maxOf(result, value)
            }
            if ((right and 1) != 0) {
                val value = tree[--right]
                result = if (minimum) minOf(result, value) else maxOf(result, value)
            }
            left /= 2
            right /= 2
        }
        return result
    }

    private fun edge(start: Int, end: Int, seed: Float, minimum: Boolean): Float {
        var value = seed
        val first = start * channels
        val last = end * channels
        if (minimum) for (i in first until last) value = minOf(value, pcm[i])
        else for (i in first until last) value = maxOf(value, pcm[i])
        return value
    }

    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = this === other || (other is StudioPcm && hash == other.hash &&
        channels == other.channels && sampleRate == other.sampleRate && pcm.contentEquals(other.pcm))

    companion object {
        private const val MAX_WAVE_BLOCKS = 16_384
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000

        /** Copies caller-owned data once; no mutable array ever escapes the asset. */
        fun of(channels: Int, sampleRate: Int, interleaved: FloatArray): StudioPcm {
            validateShape(channels, sampleRate, interleaved.size)
            return StudioPcm(channels, sampleRate, interleaved.copyOf())
        }

        internal inline fun decode(channels: Int, sampleRate: Int, count: Int, read: (Int) -> Float): StudioPcm {
            validateShape(channels, sampleRate, count)
            val pcm = FloatArray(count)
            for (i in pcm.indices) pcm[i] = read(i)
            return StudioPcm(channels, sampleRate, pcm)
        }

        internal fun validateShape(channels: Int, sampleRate: Int, count: Int) {
            require(channels in 1..2) { "Studio PCM must be mono or stereo" }
            require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Studio PCM rate must be 8000–192000 Hz" }
            require(count in 1..MAX_BYTES / Float.SIZE_BYTES) { "A Studio sample must fit in 16 MiB decoded; trim the source first" }
            require(count % channels == 0) { "Studio PCM has a partial frame" }
        }
    }
}

/** IDs are document-local. Banks share all unchanged primitive PCM arrays across edits and undo. */
class StudioAssets private constructor(private val entries: Map<Int, StudioPcm>, val decodedBytes: Int) {
    // Never expose a mutable JVM map view backed by the asset bank.
    val ids: Set<Int> get() = entries.keys.toSet()
    operator fun get(id: Int): StudioPcm? = entries[id]

    fun add(pcm: StudioPcm): StudioAssetAddition {
        entries.entries.firstOrNull { it.value == pcm }?.let { return StudioAssetAddition(this, it.key) }
        val bytes = pcm.sampleCount * Float.SIZE_BYTES
        require(bytes <= MAX_BYTES - decodedBytes) { "Studio samples exceed the 32 MiB decoded project budget" }
        val id = (1..MAX_ASSETS).firstOrNull { it !in entries } ?: error("All $MAX_ASSETS Studio asset slots are in use")
        return StudioAssetAddition(StudioAssets(entries + (id to pcm), decodedBytes + bytes), id)
    }

    fun retain(ids: Set<Int>): StudioAssets = if (entries.keys.all { it in ids }) this else
        fromEntries(entries.filterKeys { it in ids })

    override fun hashCode(): Int = entries.hashCode()
    override fun equals(other: Any?): Boolean = this === other || (other is StudioAssets && entries == other.entries)

    companion object {
        const val MAX_ASSETS = 32
        const val MAX_BYTES = 32 * 1024 * 1024
        val EMPTY = StudioAssets(emptyMap(), 0)

        internal fun fromEntries(entries: Map<Int, StudioPcm>): StudioAssets {
            if (entries.isEmpty()) return EMPTY
            require(entries.keys.all { it in 1..MAX_ASSETS }) { "Invalid Studio sample asset ID" }
            val bytes = entries.values.sumOf { it.sampleCount * Float.SIZE_BYTES }
            require(bytes <= MAX_BYTES) { "Studio samples exceed the 32 MiB decoded project budget" }
            return StudioAssets(entries.toMap(), bytes)
        }
    }
}

data class StudioAssetAddition(val assets: StudioAssets, val id: Int)
