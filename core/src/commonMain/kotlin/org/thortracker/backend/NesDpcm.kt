package org.thortracker.backend

import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm
import org.thortracker.system.nes.NesRules

/**
 * Where an NES song's DPCM samples go and which DPCM mappings the player's sample list holds, as FamiStudio 4.5.3's
 * music export lays them out without bank switching (`FamitoneMusicFile.OutputDPCMMappings`,
 * `Project.GetPackedSampleData`): the samples the song plays, in sample order, from `$C000`, each padded with `$55` to
 * a 64-byte boundary; one 4-byte list entry per distinct mapping (`$4012` offset ÷ 64, `$4013` (length − 1) ÷ 16,
 * `$4010` rate | loop × 64, `$4011` initial level). A DPCM note plays list entry k as note byte k. Identical sample
 * data is stored once.
 */
internal object NesDpcm {
    /** Most list entries the player addresses without FamiStudio's extended DPCM range. */
    const val MAX_ENTRIES = 63

    /** One sample list entry: the placed sample's [offset] from `$C000`, its [size], rate, loop and start level. */
    data class Entry(val offset: Int, val size: Int, val pitch: Int, val loop: Boolean, val level: Int) {
        /** The 4 bytes of FamiStudio's sample list. */
        fun bytes(): IntArray = intArrayOf(offset / Dpcm.ALIGN, (size - 1) / 16, pitch or (if (loop) 0x40 else 0), level)
    }

    class Layout(
        /** Song sample index → offset from `$C000`, or -1 when not in memory. */
        val offsets: IntArray,
        /** Sample memory from `$C000`: each sample padded with `$55` to 64 bytes (never past the memory's end). */
        val bytes: ByteArray,
        /** The song's sample indices in memory, in placement order (one per distinct data). */
        val placed: List<Int>,
        /** List entries; entry i plays as note byte i + 1. The ones the song plays come first. */
        val entries: List<Entry>,
        /** Entries the song plays; the rest only serve auditioning other mappings. */
        val played: Int,
        /** (instrument slot, absolute note) → 1-based entry. */
        val index: Map<Pair<Int, Int>, Int>,
        /** Bytes from `$C000` up to the end of the last sample the song plays, had they all fitted. */
        val playedEnd: Int,
        /** Distinct mappings the song plays, had the list room for all. */
        val playedMappings: Int,
    )

    /**
     * The layout of [fs]: first the samples and mappings its DPCM notes play (samples in sample order, mappings in
     * order of first use); with [withUnplayed] (the preview image) then every other mapping of every instrument while
     * the list and the memory have room, so the editor can audition them.
     */
    fun layout(fs: FsSong, withUnplayed: Boolean): Layout {
        val samples = fs.samples
        val used = LinkedHashSet<Pair<Int, Int>>() // (slot, absolute note) in order of first use
        for (n in fs.notes[NesRules.DPCM]) {
            if (n == null || !n.isMusical || n.instrument == 0) continue
            val note = n.value + NesRules.NOTE_OFFSET
            if (fs.instruments[n.instrument - 1].dpcm[note]?.sample in samples.indices) used += n.instrument to note
        }
        val usedSamples = used.map { (slot, note) -> fs.instruments[slot - 1].dpcm.getValue(note).sample }.toSortedSet()
        val playedEnd = run {
            var end = 0
            val seen = HashSet<List<Int>>()
            for (s in usedSamples) if (seen.add(samples[s].data)) end = Dpcm.aligned(end) + samples[s].data.size
            end
        }
        val firstWithData = HashMap<List<Int>, Int>()
        val offsets = IntArray(samples.size) { -1 }
        val placed = ArrayList<Int>()
        val out = Bytes()
        fun pad(to: Int) { while (out.size < to) out.byte(0x55) }
        fun place(s: Int): Boolean {
            if (offsets[s] >= 0) return true
            val data = samples[s].data
            firstWithData[data]?.let { offsets[s] = offsets[it]; return true }
            val at = Dpcm.aligned(out.size)
            if (at + data.size > Dpcm.MEMORY) return false
            pad(at)
            offsets[s] = at
            firstWithData[data] = s
            placed += s
            for (b in data) out.byte(b)
            return true
        }
        for (s in usedSamples) place(s)
        val entries = ArrayList<Entry>()
        val index = LinkedHashMap<Pair<Int, Int>, Int>()
        val distinct = HashSet<Entry>()
        fun entry(slot: Int, note: Int, count: Boolean) {
            val m = fs.instruments[slot - 1].dpcm.getValue(note)
            val sample = samples[m.sample]
            val e = Entry(offsets[m.sample], sample.data.size, m.pitch, m.loop, 2 * (if (m.dmcInitial >= 0) m.dmcInitial else sample.dmcInitial))
            if (count) distinct += e
            if (e.offset < 0) return
            var k = entries.indexOf(e) + 1
            if (k == 0) {
                if (entries.size >= MAX_ENTRIES) return
                entries += e
                k = entries.size
            }
            index[slot to note] = k
        }
        for ((slot, note) in used) entry(slot, note, count = true)
        val played = entries.size
        if (withUnplayed) {
            for ((i, ins) in fs.instruments.withIndex()) for (note in ins.dpcm.keys.sorted()) {
                if ((i + 1 to note) in index) continue
                val sample = ins.dpcm.getValue(note).sample
                if (sample in samples.indices && place(sample)) entry(i + 1, note, count = false)
            }
        }
        pad(minOf(Dpcm.aligned(out.size), Dpcm.MEMORY))
        return Layout(offsets, out.toByteArray(), placed, entries, played, index, playedEnd, distinct.size)
    }

    /** The layout of the samples and mappings [song] plays. Requires a song without structural errors. */
    fun layout(song: Song): Layout = layout(FsSong.of(song), withUnplayed = false)

    /** Bytes of sample memory [song] plays from `$C000` (see [Layout.playedEnd]). */
    fun memoryUsed(song: Song): Int = layout(song).playedEnd
}
