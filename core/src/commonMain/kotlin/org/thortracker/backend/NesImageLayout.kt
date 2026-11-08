package org.thortracker.backend

/**
 * The image the native `"nes-famistudio"` engine loads (docs/AUDIO_ENGINE.md, NES image layout): a 32-byte header,
 * the envelope directory, then CPU memory from [NesPlayerImage.SONG_BASE] to [NesPlayerImage.SAMPLE_END]: the song data
 * at its start, the DPCM samples at [NesPlayerImage.SAMPLE_BASE] (zero everywhere else).
 */
internal object NesImageLayout {
    const val SIZE = NesPlayerImage.IMAGE_SIZE
    private const val REGION = NesPlayerImage.HEADER_SIZE + NesPlayerImage.DIRECTORY_SIZE

    /** Writes [lowered] as an image into [out] ([SIZE] bytes). */
    fun build(lowered: FamiStudioLowering.Lowered, out: ByteArray) {
        require(out.size == SIZE) { "NES image buffer is ${out.size} bytes; the engine needs $SIZE" }
        val data = lowered.data
        require(data.size <= NesPlayerImage.SONG_END - NesPlayerImage.SONG_BASE) { "NES song data is ${data.size} bytes; the region holds ${NesPlayerImage.SONG_END - NesPlayerImage.SONG_BASE}" }
        val samples = lowered.samples
        require(samples.size <= NesPlayerImage.SAMPLE_END - NesPlayerImage.SAMPLE_BASE) { "DPCM samples need ${samples.size} bytes; the NES holds ${NesPlayerImage.SAMPLE_END - NesPlayerImage.SAMPLE_BASE}" }
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
        for (e in lowered.envelopes) {
            val at = NesPlayerImage.HEADER_SIZE + 4 * e.index
            put16(out, at, e.address)
            out[at + 2] = e.bytes.size.toByte()
            out[at + 3] = e.kind.ordinal.toByte()
        }
        data.copyInto(out, REGION)
        samples.copyInto(out, REGION + NesPlayerImage.SAMPLE_BASE - NesPlayerImage.SONG_BASE)
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
