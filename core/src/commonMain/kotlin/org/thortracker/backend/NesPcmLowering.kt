package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.Pcm
import org.thortracker.system.nes.nes

/**
 * The MMC5 PCM lane's image extension: the bytes after the seven-channel FamiStudio image that the native
 * `"nes-famistudio-mmc5"` engine's software PCM player reads (layout: app/src/main/cpp/nes/nes_mmc5_pcm.h and
 * docs/AUDIO_ENGINE.md, "MMC5 PCM lane"). FamiStudio's engine never sees it, so the seven-channel image and every
 * export of it stay what they were; the `.nsf` and FamiStudio text exports refuse songs whose PCM lane plays ([plays]).
 *
 * Every mapping of every sound is in the table (the player's table holds 64 slots x 96 keys, so editing and
 * auditioning never run out of entries), in slot then key order, each carrying its [binding] (slot and key), which
 * the player follows across live edits instead of table positions; every sample is in the data, in sample order.
 * Events: one per PCM-lane row that does something (a note, OFF, Cxx, Gxx, Sxx; Fxx belongs to the engine's speed,
 * [FsSong]), per played order, identical orders sharing one range.
 */
internal object NesPcmLowering {
    const val MAGIC = 0x43505454 // "TTPC"
    const val VERSION = 1
    const val MAX_ORDERS = 255
    const val MAX_ROWS = Songs.ROWS
    const val MAX_EVENTS = MAX_ORDERS * MAX_ROWS
    const val HEADER_SIZE = 16
    const val ORDERS_AT = HEADER_SIZE
    const val SAMPLES_AT = ORDERS_AT + MAX_ORDERS * 4
    const val MAPPINGS_AT = SAMPLES_AT + Pcm.MAX_SAMPLES * 12
    const val EVENTS_AT = MAPPINGS_AT + Pcm.MAX_MAPPINGS * 8
    const val DATA_AT = EVENTS_AT + MAX_EVENTS * 6
    const val SIZE = DATA_AT + Pcm.MEMORY

    const val EV_NOTE = 1
    const val EV_STOP = 2
    const val EV_VOLUME = 4
    const val EV_DELAY = 8
    const val EV_CUT = 16

    init {
        check(SAMPLES_AT == 1036 && MAPPINGS_AT == 1804 && EVENTS_AT == 50956 && DATA_AT == 148876 && SIZE == 411020) {
            "the PCM extension layout of nes_mmc5_pcm.h"
        }
    }

    /** The engine image size of songs with [expansion]: the FamiStudio image, plus the extension on the MMC5. */
    fun imageSize(expansion: NesExpansion): Int = expansion.player.imageSize + if (expansion.hasPcmLane) SIZE else 0

    /** Keys per instrument slot in a [binding]: the NES note range C-1..B-8. */
    const val KEYS = NesRules.NOTE_COUNT

    /**
     * The stable identity of instrument [slot]'s (1-based) mapping of absolute [key]: (slot - 1) x 96 + (key - 12),
     * 0..6143, ascending in slot then key order. Mapping entry bytes 2-3; table indices shift as keys are mapped or
     * unmapped, bindings never do.
     */
    fun binding(slot: Int, key: Int): Int {
        require(slot in 1..NesSystem.SLOTS && key in NesSystem.noteRange) { "No PCM binding for slot $slot, key $key" }
        return (slot - 1) * KEYS + (key - NesSystem.noteRange.first)
    }

    /**
     * Mapping table index of every (slot 1-based, key) the song's sounds map, in slot then key order (the order of their
     * [binding]s): what the lane's events and the native audition note (index + 1) name in this song's extension.
     */
    fun mappings(song: Song): Map<Pair<Int, Int>, Int> {
        val index = LinkedHashMap<Pair<Int, Int>, Int>()
        for ((i, ins) in song.nes.instruments.withIndex()) for (key in ins.pcm.keys.sorted()) index[i + 1 to key] = index.size
        return index
    }

    /** Where the arrangement of [document] first plays a note on the PCM lane ("Pattern 02, MMC5 PCM row 4"), or null. */
    fun firstNote(document: Song): String? {
        if (!document.nes.expansion.hasPcmLane) return null
        val song = NesSystem.arranged(document)
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (r in 0 until pattern.length) {
                if (pattern.tracks[NesRules.MMC5_PCM][r].note >= 0) {
                    return "${Songs.patternLabel(song, p).replaceFirstChar { it.uppercase() }}, MMC5 PCM row $r"
                }
            }
        }
        return null
    }

    /** True when the arrangement plays the PCM lane (a note on it in a played order). */
    fun plays(document: Song): Boolean = firstNote(document) != null

    /**
     * Why the `.nsf` and FamiStudio text exports cannot hold [document], or null: FamiStudio 4.5.3's engine has no MMC5
     * PCM, so a song whose PCM lane plays would lose it. PCM samples and mappings that nothing plays stay in the
     * project only; a PCM-lane Fxx is exported as the song speed it is.
     */
    fun exportProblem(document: Song, format: String): String? = firstNote(document)?.let {
        "The MMC5 PCM lane plays ($it): $format runs FamiStudio 4.5.3's engine, which cannot play the MMC5's PCM (Thor " +
            "Tracker plays that lane with its own software player). Export a WAV, or clear the PCM lane's notes for a " +
            "seven-channel $format"
    }

    /**
     * The extension for [document] (an MMC5 song without errors) into [out] at [at] ([SIZE] bytes).
     * @throws IllegalArgumentException when it does not fit the player (the checks name the limit).
     */
    fun build(document: Song, out: ByteArray, at: Int) {
        val song = NesSystem.arranged(document)
        val sound = song.nes
        require(sound.expansion.hasPcmLane) { "Only MMC5 songs have a PCM lane" }
        require(song.orders.size <= MAX_ORDERS) { "The song has ${song.orders.size} orders; the PCM lane holds $MAX_ORDERS" }
        out.fill(0, at, at + SIZE)
        fun put16(i: Int, v: Int) { out[at + i] = v.toByte(); out[at + i + 1] = (v shr 8).toByte() }
        fun put32(i: Int, v: Int) { put16(i, v); put16(i + 2, v ushr 16) }
        // Samples.
        require(sound.pcmSamples.size <= Pcm.MAX_SAMPLES) { "The song has ${sound.pcmSamples.size} PCM samples; the player holds ${Pcm.MAX_SAMPLES}" }
        val total = Pcm.stored(sound)
        require(total <= Pcm.MEMORY) {
            "The PCM samples take ${Pcm.kb(total)}; the MMC5 PCM player holds ${Pcm.kb(Pcm.MEMORY)}. Delete or shorten samples"
        }
        var offset = 0
        for ((s, sample) in sound.pcmSamples.withIndex()) {
            put32(SAMPLES_AT + 12 * s, offset)
            put32(SAMPLES_AT + 12 * s + 4, sample.data.size)
            put32(SAMPLES_AT + 12 * s + 8, sample.loopStart)
            sample.data.copyInto(out, at + DATA_AT + offset)
            offset += sample.data.size
        }
        // Mappings.
        val index = mappings(song)
        require(index.size <= Pcm.MAX_MAPPINGS) { "The sounds map ${index.size} PCM keys; the player holds ${Pcm.MAX_MAPPINGS}" }
        for ((key, i) in index) {
            val m = sound.instruments[key.first - 1].pcm.getValue(key.second)
            val sample = sound.pcmSamples[m.sample]
            val e = MAPPINGS_AT + 8 * i
            out[at + e] = m.sample.toByte()
            out[at + e + 1] = (if (m.loop) 1 else 0).toByte()
            put16(e + 2, binding(key.first, key.second))
            put32(e + 4, Pcm.period(sample.rate, m.pitch).toInt())
        }
        // Events, per played order (identical orders share their range).
        val ranges = HashMap<List<Int>, Pair<Int, Int>>()
        val events = ArrayList<Int>() // 6 bytes per event
        var current = 0 // the lane's instrument along the played order: a note without one plays this
        for ((o, p) in song.orders.withIndex()) {
            val pattern = song.patterns[p]
            val bytes = ArrayList<Int>()
            for (r in 0 until pattern.length) {
                val cell = pattern.tracks[NesRules.MMC5_PCM][r]
                var flags = 0
                var mapping = 0
                var packed = 0
                if (cell.note >= 0) {
                    val slot = if (cell.instrument != 0) cell.instrument else current
                    require(slot != 0) { "${where(song, p, r)}: ${NesSystem.noteName(cell.note)} has no instrument and none has played on MMC5 PCM yet" }
                    mapping = index[slot to cell.note] ?: throw IllegalArgumentException(
                        "${where(song, p, r)}: ${NesRules.pcmMappingProblem(song, slot, cell.note) ?: "no PCM mapping"}")
                    current = slot
                    flags = flags or EV_NOTE
                } else if (cell.note == Cell.CUT) {
                    flags = flags or EV_STOP
                }
                if (cell.hasEffect) when (cell.effect) {
                    NesRules.FX_VOLUME -> { flags = flags or EV_VOLUME; packed = packed or (cell.param and 15) }
                    NesRules.FX_NOTE_DELAY -> if (flags and (EV_NOTE or EV_STOP) != 0) {
                        flags = flags or EV_DELAY
                        packed = packed or ((cell.param and 31) shl 4)
                    }
                    NesRules.FX_NOTE_CUT -> { flags = flags or EV_CUT; packed = packed or ((cell.param and 31) shl 9) }
                }
                if (flags == 0) continue
                bytes += listOf(r, flags, mapping and 0xFF, mapping shr 8, packed and 0xFF, packed shr 8)
            }
            val (first, count) = ranges.getOrPut(bytes) {
                val first = events.size / 6
                events += bytes
                first to bytes.size / 6
            }
            put16(ORDERS_AT + 4 * o, first)
            put16(ORDERS_AT + 4 * o + 2, count)
        }
        val eventCount = events.size / 6
        require(eventCount <= MAX_EVENTS) { "The PCM lane needs $eventCount events; the player holds $MAX_EVENTS" }
        for ((i, b) in events.withIndex()) out[at + EVENTS_AT + i] = b.toByte()
        // Header.
        put32(0, MAGIC)
        out[at + 4] = VERSION.toByte()
        out[at + 5] = sound.pcmSamples.size.toByte()
        put16(6, index.size)
        put32(8, total)
        put16(12, eventCount)
        out[at + 14] = song.orders.size.toByte()
    }

    private fun where(song: Song, pattern: Int, row: Int) = "${Songs.patternLabel(song, pattern).replaceFirstChar { it.uppercase() }}, MMC5 PCM row $row"
}
