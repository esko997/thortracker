package org.thortracker.backend

/**
 * The image the native `"nes-famistudio"` engine loads (docs/AUDIO_ENGINE.md, NES image layout): a 32-byte header,
 * the envelope directory, then CPU memory from [NesPlayerImage.SONG_BASE] to [NesPlayerImage.REGION_END]: the song data
 * at its start and the DPCM sample block at its start address (header 24) at the top, zero everywhere else.
 */
internal object NesImageLayout {
    const val SIZE = NesPlayerImage.IMAGE_SIZE
    private const val REGION = NesPlayerImage.HEADER_SIZE + NesPlayerImage.DIRECTORY_SIZE

    /** Writes [lowered] as an image into [out] ([SIZE] bytes). */
    fun build(lowered: FamiStudioLowering.Lowered, out: ByteArray) {
        require(out.size == SIZE) { "NES image buffer is ${out.size} bytes; the engine needs $SIZE" }
        val data = lowered.data
        val samples = lowered.samples
        val start = lowered.sampleStart
        val songEnd = if (samples.isEmpty()) NesPlayerImage.REGION_END else start
        require(samples.isEmpty() || start >= NesPlayerImage.SAMPLE_BASE && start % NesPlayerImage.SAMPLE_ALIGN == 0 &&
            start + samples.size <= NesPlayerImage.REGION_END) { "DPCM samples need ${samples.size} bytes; they do not fit \$C000-\$FFF9" }
        require(NesPlayerImage.SONG_BASE + data.size <= songEnd) { "NES song data is ${data.size} bytes; with the samples the region holds ${songEnd - NesPlayerImage.SONG_BASE}" }
        require(lowered.mappingCount <= NesPlayerImage.MAX_DPCM_MAPPINGS) { "The song needs ${lowered.mappingCount} DPCM mappings; the player holds ${NesPlayerImage.MAX_DPCM_MAPPINGS}" }
        val count = (lowered.envelopes.maxOfOrNull { it.index } ?: -1) + 1
        require(count <= NesPlayerImage.MAX_ENVELOPES) { "The song needs $count envelopes; the engine holds ${NesPlayerImage.MAX_ENVELOPES}" }
        out.fill(0)
        "TTNS".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        put32(out, 4, NesPlayerImage.PLAYER_ID)
        put16(out, 8, NesPlayerImage.SONG_BASE)
        put16(out, 10, data.size)
        out[12] = 0 // song index
        out[13] = NesPlayerImage.LAYOUT_THOR.toByte()
        out[14] = lowered.instrumentCount.toByte()
        out[NesPlayerImage.HEADER_DPCM_COUNT] = lowered.mappingCount.toByte()
        put16(out, 16, count)
        put16(out, NesPlayerImage.HEADER_SAMPLE_SIZE, samples.size)
        put16(out, NesPlayerImage.HEADER_SAMPLE_START, if (samples.isEmpty()) 0 else start)
        for (e in lowered.envelopes) {
            val at = NesPlayerImage.HEADER_SIZE + 4 * e.index
            put16(out, at, e.address)
            out[at + 2] = e.bytes.size.toByte()
            out[at + 3] = e.kind.ordinal.toByte()
        }
        data.copyInto(out, REGION)
        if (samples.isNotEmpty()) samples.copyInto(out, REGION + start - NesPlayerImage.SONG_BASE)
    }

    private fun put16(out: ByteArray, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v shr 8).toByte()
    }

    private fun put32(out: ByteArray, at: Int, v: Int) {
        put16(out, at, v)
        put16(out, at + 2, v ushr 16)
    }
}
