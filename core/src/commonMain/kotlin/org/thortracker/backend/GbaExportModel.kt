package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.system.gba.GbaInstrument
import org.thortracker.system.gba.GbaSample
import org.thortracker.system.gba.GbaSound

/** Shared host-independent compilation; neither output is parsed to produce the other. */
internal class GbaExportModel(document: Song) {
    val song: Song
    val sound: GbaSound
    val patterns: List<Pattern>
    val orders: List<Int>
    val instruments: List<Instrument>
    val samples: List<Sample>

    data class Sample(val source: GbaSample, val pitch: GbaSamplePitch) {
        val volume get() = source.volume
        val pan get() = source.pan and 254
        val loopLength get() = source.loopEnd - source.loopStart
    }
    data class Instrument(val source: GbaInstrument, val samples: List<Int>, val keymap: List<Int>)

    init {
        require(document.sound is GbaSound) { "Expected a GBA/Maxmod song" }
        sound = document.sound
        val errors = GbaExportConstraints.errors(sound)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        require(document.tuning == null) { "GBA XM export does not support microtuning" }
        require(document.ticksPerRow in 1..31) { "XM initial speed must be 1..31" }
        require(document.orders.isNotEmpty() && document.orders.all { it in document.patterns.indices }) { "Invalid GBA order list" }
        require(document.loopOrder in document.orders.indices) { "Invalid loop order" }
        require(document.parkedOrders.all { it in document.orders.indices } && document.parkedOrders.distinct().size == document.parkedOrders.size) { "Invalid parked orders" }
        require(document.parkedOrders.size < document.orders.size) { "At least one order must play" }
        song = document.system.arranged(document)
        require(song.orders.size <= 199) { "Maxmod supports at most 199 playing orders" }
        val usedPatterns = song.orders.distinct()
        patterns = usedPatterns.map { song.patterns[it] }
        orders = song.orders.map { usedPatterns.indexOf(it) }
        val flatSamples = mutableListOf<Sample>()
        val silent = GbaSample(name = "Unmapped silence", pcm = List(4) { 0 }, volume = 0)
        instruments = sound.instruments.map { inst ->
            val local = inst.keymap.distinct().filter { it >= 0 }.toMutableList()
            if (local.isNotEmpty() && -1 in inst.keymap) local += -1
            val indices = local.map { index ->
                val sample = if (index < 0) silent else sound.samples[index]
                flatSamples.add(Sample(sample, GbaExportConstraints.pitch(sample)))
                flatSamples.lastIndex
            }
            Instrument(inst, indices, inst.keymap.map { local.indexOf(it) })
        }
        samples = flatSamples
        patterns.forEachIndexed { p, pattern ->
            require(pattern.length in 1..256 && pattern.tracks.size == 8 && pattern.tracks.all { it.size >= pattern.length }) { "Pattern $p needs eight tracks and 1..256 rows" }
            pattern.tracks.forEachIndexed { channel, track ->
                for (row in 0 until pattern.length) {
                    val cell = track[row]
                    val where = "Pattern $p row $row channel ${channel + 1}: "
                    require(cell.note in 0..95 || cell.note == Cell.EMPTY || cell.note == Cell.CUT) { where + "invalid XM note" }
                    require(cell.instrument in 0..64 && cell.more.isEmpty()) { where + "invalid instrument or extra effect column" }
                    GbaExportConstraints.effectError(cell.effect, cell.param)?.let { throw IllegalArgumentException(where + it) }
                    if (cell.instrument > 0) require(instruments[cell.instrument - 1].samples.isNotEmpty()) { where + "instrument has no mapped samples" }
                    if (cell.effect == 11) require(cell.param in song.orders.indices) { where + "jump order is outside the arrangement" }
                    GbaExportConstraints.sampleOffsetError(sound, cell)?.let { throw IllegalArgumentException(where + it) }
                }
            }
        }
    }
}

/** Bounded little-endian writer, used only during compilation (not in the audio callback). */
internal class GbaBytes(capacity: Int = GbaPlayerImage.SIZE) {
    private val bytes = ByteArray(capacity)
    var size = 0
        private set
    fun u8(value: Int) { require(size < bytes.size) { "GBA export exceeds its size limit" }; bytes[size++] = value.toByte() }
    fun u16(value: Int) { u8(value); u8(value ushr 8) }
    fun u32(value: Int) { u16(value); u16(value ushr 16) }
    fun zeros(count: Int) { repeat(count) { u8(0) } }
    fun text(value: String, count: Int) { repeat(count) { u8(value.getOrNull(it)?.code?.takeIf { code -> code in 32..126 } ?: 0) } }
    fun append(value: ByteArray) { require(value.size <= bytes.size - size) { "GBA export exceeds its size limit" }; value.copyInto(bytes, size); size += value.size }
    fun align() { while (size and 3 != 0) u8(0xBA) }
    fun patch32(at: Int, value: Int) { require(at >= 0 && at <= size - 4); repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() } }
    fun result(): ByteArray = bytes.copyOf(size)
}
