package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.opl3.Opl3Instrument
import org.thortracker.system.opl3.Opl3Sound
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opl3.opl3

/**
 * Lowers an OPL3 song into the image Thor Tracker's OPL3 player (native `"opl3-fm"`, `opl3/opl3_player.c`) plays and
 * exports as VGM: the layout of docs/AUDIO_ENGINE.md "OPL3: Image layout", byte for byte what the reference builder
 * `tools/audio/opl3/opl3_song.py` (`build()`) writes for the same song (`Opl3GoldenTest`).
 *
 * Orders are the song's arrangement ([Opl3System.arranged]); each order's rows = its pattern's length; each
 * (order, channel) is that track's rows encoded as the player reads them, and byte-identical encodings are stored once
 * in first-use order (orders, then channels). Instruments are written for every filled slot (the image's instrument
 * count is the highest), so the editor can audition any of them.
 */
object Opl3Lowering {
    const val IMAGE_SIZE = 65536
    const val LAYOUT = 1
    const val OFF_ROWS = 64
    const val OFF_ORDERS = 320
    const val OFF_INSTRUMENTS = 4928
    const val OFF_SHAPES = 6976
    const val OFF_DATA = 7232
    const val INSTRUMENT_SIZE = 32
    const val CHANNELS = 9

    /** Bytes of pattern data the image holds. */
    const val DATA_CAPACITY = IMAGE_SIZE - OFF_DATA

    const val NOTE_OFF = 0xFE
    const val FLAG_DEFINED = 0x80
    const val FLAG_FOUR_OP = 0x01

    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's pattern data does not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The OPL3 image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = Opl3System.arranged(document)
        val sound = song.opl3
        out[0] = 'T'.code.toByte(); out[1] = 'T'.code.toByte(); out[2] = 'O'.code.toByte(); out[3] = '3'.code.toByte()
        out[4] = LAYOUT.toByte()
        out[5] = sound.multispeed.toByte()
        out[6] = song.ticksPerRow.toByte()
        out[7] = sound.depthByte.toByte()
        u16(out, 8, song.orders.size)
        u16(out, 10, song.loopOrder)
        out[12] = highestSlot(sound).toByte()
        out[13] = sound.chords.size.toByte()
        sound.instruments.forEachIndexed { i, ins -> if (ins != null) record(ins).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i) }
        sound.chords.forEachIndexed { k, chord -> chord.intervals.forEachIndexed { i, v -> out[OFF_SHAPES + 4 * k + i] = v.toByte() } }

        val blobs = LinkedHashMap<Blob, Int>()
        var size = 0
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            out[OFF_ROWS + o] = (pattern.length and 0xFF).toByte()
            for (c in 0 until CHANNELS) {
                val blob = Blob(encode(pattern.tracks[c], pattern.length))
                val at = blobs.getOrPut(blob) {
                    val start = size
                    size += blob.bytes.size
                    require(size <= DATA_CAPACITY) { dataTooLarge(song) }
                    blob.bytes.copyInto(out, OFF_DATA + start)
                    start
                }
                u16(out, OFF_ORDERS + 2 * (CHANNELS * o + c), at)
            }
        }
        u16(out, 14, size)
    }

    /** Bytes of pattern data [document] lowers to (after sharing identical tracks). */
    fun dataSize(document: Song): Int {
        val song = Opl3System.arranged(document)
        val seen = HashSet<Blob>()
        var size = 0
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (c in 0 until CHANNELS) {
                val blob = Blob(encode(pattern.tracks[c], pattern.length))
                if (seen.add(blob)) size += blob.bytes.size
            }
        }
        return size
    }

    private fun dataTooLarge(song: Song): String =
        "The song's pattern data needs ${(dataSize(song) + 1023) / 1024} KB; the OPL3 image holds ${DATA_CAPACITY / 1024} KB. " +
            "Reuse patterns or shorten the song"

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: Opl3Sound): Int = sound.instruments.indexOfLast { it != null } + 1

    /** The 32-byte instrument record; a 2-op instrument's operators 3 and 4 are zero. */
    fun record(ins: Opl3Instrument): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        r[0] = (FLAG_DEFINED or (if (ins.fourOp) FLAG_FOUR_OP else 0)).toByte()
        r[1] = ins.algorithm.toByte()
        r[2] = ins.feedback.toByte()
        for (k in 0 until ins.operatorCount) {
            val op = ins.operators[k]
            r[4 + 5 * k] = op.reg20.toByte()
            r[5 + 5 * k] = op.reg40.toByte()
            r[6 + 5 * k] = op.reg60.toByte()
            r[7 + 5 * k] = op.reg80.toByte()
            r[8 + 5 * k] = op.regE0.toByte()
        }
        r[24] = ins.sweep.start.toByte()
        r[25] = ins.sweep.end.toByte()
        r[26] = ins.sweep.speed.toByte()
        return r
    }

    /**
     * [rows] rows of [track] as the player reads them: empty rows in runs (`0x80 | k` = k + 1 rows), else a mask (bit 0
     * note, bit 1 instrument, bits 2-4 effect count), the note (`0xFE` = OFF), the slot and each non-blank effect
     * column's code and parameter in column order.
     */
    fun encode(track: List<Cell>, rows: Int): ByteArray {
        val out = ArrayList<Byte>(rows * 2)
        var empty = 0
        fun flush() {
            while (empty > 0) {
                val k = minOf(empty, 128)
                out += (0x80 or (k - 1)).toByte()
                empty -= k
            }
        }
        for (r in 0 until rows) {
            val cell = track[r]
            if (cell.isBlank) { empty++; continue }
            flush()
            var effects = 0
            for (col in 0..cell.more.size) if (cell.effectCode(col) != 0 || cell.effectParam(col) != 0) effects++
            val hasNote = cell.note != Cell.EMPTY
            val hasInstrument = cell.instrument != 0
            if (!hasNote && !hasInstrument && effects == 0) { empty++; continue }
            out += ((if (hasNote) 1 else 0) or (if (hasInstrument) 2 else 0) or (effects shl 2)).toByte()
            if (hasNote) out += (if (cell.note == Cell.CUT) NOTE_OFF else cell.note).toByte()
            if (hasInstrument) out += cell.instrument.toByte()
            for (col in 0..cell.more.size) {
                val code = cell.effectCode(col)
                val param = cell.effectParam(col)
                if (code == 0 && param == 0) continue
                out += code.toByte()
                out += param.toByte()
            }
        }
        flush()
        return out.toByteArray()
    }

    private fun u16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = (value shr 8 and 0xFF).toByte()
    }

    /** Encoded track bytes with content equality, for sharing. */
    private class Blob(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Blob && other.bytes.contentEquals(bytes)
    }
}
