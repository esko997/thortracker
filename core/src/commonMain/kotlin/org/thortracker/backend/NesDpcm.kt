package org.thortracker.backend

import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm
import org.thortracker.system.nes.NesRules

/**
 * Where an NES song's DPCM samples go and which DPCM mappings the player's sample list holds, packed as FamiStudio
 * 4.5.3's music export packs them without bank switching (`FamitoneMusicFile.OutputDPCMMappings`,
 * `Project.GetPackedSampleData`): the samples the song plays, in sample order, each padded with `$55` to a 64-byte
 * boundary (identical data stored once); one 4-byte list entry per distinct mapping (`$4012` address, `$4013`
 * (length − 1) ÷ 16, `$4010` rate | loop × 64, `$4011` initial level). A DPCM note plays list entry k as note byte k.
 *
 * Song data and samples share the region after the player (`$8C00-$FFF9` on the 2A03 build; docs/AUDIO_ENGINE.md):
 * the song grows up from [NesPlayer.songBase], the sample block sits as high as it can ([Layout.start], 64-aligned, at
 * least [NesPlayerImage.SAMPLE_BASE], the lowest address the DMC reads), and the song must end at or below it.
 */
internal object NesDpcm {
    /** Most list entries the player addresses without FamiStudio's extended DPCM range. */
    const val MAX_ENTRIES = 63

    /** One sample list entry: the sample's [offset] in the sample block, its [size], rate, loop and start level. */
    data class Entry(val offset: Int, val size: Int, val pitch: Int, val loop: Boolean, val level: Int)

    class Layout(
        /** Song sample index → offset in the sample block, or -1 when not in it. */
        val offsets: IntArray,
        /** The sample block: each sample padded with `$55` to 64 bytes (FamiStudio's `.dmc`). */
        val bytes: ByteArray,
        /** The song's sample indices in the block, in placement order (one per distinct data). */
        val placed: List<Int>,
        /** List entries; entry i plays as note byte i + 1. The ones the song plays come first. */
        val entries: List<Entry>,
        /** Entries the song plays; the rest only serve auditioning other mappings. */
        val played: Int,
        /** (instrument slot, absolute note) → 1-based entry. */
        val index: Map<Pair<Int, Int>, Int>,
        /** Bytes of the samples the song plays (the block before unplayed additions). */
        val playedBytes: Int,
        /** Distinct mappings the song plays, had the list room for all. */
        val playedMappings: Int,
    ) {
        /** CPU address of the sample block, 0 without samples (it may fall below `$C000` when the samples are too big). */
        val start: Int get() = startFor(bytes.size)

        /** The 4 bytes of FamiStudio's sample list for [e]. */
        fun entryBytes(e: Entry): IntArray = intArrayOf(
            (start + e.offset - NesPlayerImage.SAMPLE_BASE) / Dpcm.ALIGN, (e.size - 1) / 16, e.pitch or (if (e.loop) 0x40 else 0), e.level,
        )
    }

    /** Where a sample block of [size] bytes starts: as high as it fits below [NesPlayerImage.REGION_END], 64-aligned. */
    fun startFor(size: Int): Int = if (size == 0) 0 else (NesPlayerImage.REGION_END - size) / Dpcm.ALIGN * Dpcm.ALIGN

    /**
     * The layout of [fs]: first the samples and mappings its DPCM notes play (samples in sample order, mappings in
     * order of first use). With [songBytes] (the preview image: the song's data size with that list) then every other
     * mapping of every instrument while the list, the sample memory and the shared budget have room, so the editor can
     * audition them; each one costs 4 list bytes and maybe a sample, and never pushes the song over.
     */
    fun layout(fs: FsSong, songBytes: Int? = null): Layout {
        val samples = fs.samples
        val used = LinkedHashSet<Pair<Int, Int>>() // (slot, absolute note) in order of first use
        for (n in fs.notes[NesRules.DPCM]) {
            if (n == null || !n.isMusical || n.instrument == 0) continue
            val note = n.value + NesRules.NOTE_OFFSET
            if (fs.instruments[n.instrument - 1].dpcm[note]?.sample in samples.indices) used += n.instrument to note
        }
        val firstWithData = HashMap<List<Int>, Int>()
        val offsets = IntArray(samples.size) { -1 }
        val placed = ArrayList<Int>()
        val out = Bytes()
        fun cost(s: Int): Int = if (offsets[s] >= 0 || samples[s].data in firstWithData) 0 else Dpcm.aligned(samples[s].data.size)
        fun place(s: Int) {
            if (offsets[s] >= 0) return
            val data = samples[s].data
            firstWithData[data]?.let { offsets[s] = offsets[it]; return }
            offsets[s] = out.size
            firstWithData[data] = s
            placed += s
            for (b in data) out.byte(b)
            while (out.size % Dpcm.ALIGN != 0) out.byte(0x55)
        }
        for (s in used.map { (slot, note) -> fs.instruments[slot - 1].dpcm.getValue(note).sample }.toSet().sorted()) place(s)
        val playedBytes = out.size
        val entries = ArrayList<Entry>()
        val index = LinkedHashMap<Pair<Int, Int>, Int>()
        val distinct = HashSet<Entry>()
        fun entryOf(slot: Int, note: Int): Entry {
            val m = fs.instruments[slot - 1].dpcm.getValue(note)
            val sample = samples[m.sample]
            return Entry(offsets[m.sample], sample.data.size, m.pitch, m.loop, 2 * (if (m.dmcInitial >= 0) m.dmcInitial else sample.dmcInitial))
        }
        fun add(e: Entry, key: Pair<Int, Int>) {
            var k = entries.indexOf(e) + 1
            if (k == 0) {
                if (entries.size >= MAX_ENTRIES) return
                entries += e
                k = entries.size
            }
            index[key] = k
        }
        for (key in used) entryOf(key.first, key.second).also { distinct += it; add(it, key) }
        val played = entries.size
        if (songBytes != null) {
            for ((i, ins) in fs.instruments.withIndex()) for (note in ins.dpcm.keys.sorted()) {
                val key = i + 1 to note
                val sample = ins.dpcm.getValue(note).sample
                if (key in index || sample !in samples.indices) continue
                val block = out.size + cost(sample)
                val listBytes = 4 * (entries.size - played + 1)
                val start = startFor(block)
                if (entries.size >= MAX_ENTRIES || block > Dpcm.MEMORY || fs.player.songBase + songBytes + listBytes > start) continue
                place(sample)
                add(entryOf(key.first, key.second), key)
            }
        }
        return Layout(offsets, out.toByteArray(), placed, entries, played, index, playedBytes, distinct.size)
    }

    /** The layout of the samples and mappings [song] plays. Requires a song without structural errors. */
    fun layout(song: Song): Layout = layout(FsSong.of(song))

    /** Bytes of the sample block [song] plays (each sample in 64-byte slots). */
    fun memoryUsed(song: Song): Int = layout(song).playedBytes
}
