package org.thortracker.backend

import org.thortracker.backend.Opl3PlayerImage.CHANNELS
import org.thortracker.backend.Opl3PlayerImage.DATA_CAPACITY
import org.thortracker.backend.Opl3PlayerImage.FLAG_DEFINED
import org.thortracker.backend.Opl3PlayerImage.FLAG_FOUR_OP
import org.thortracker.backend.Opl3PlayerImage.IMAGE_SIZE
import org.thortracker.backend.Opl3PlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.Opl3PlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.Opl3PlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.Opl3PlayerImage.MASK_NOTE
import org.thortracker.backend.Opl3PlayerImage.MAX_RUN
import org.thortracker.backend.Opl3PlayerImage.NOTE_OFF
import org.thortracker.backend.Opl3PlayerImage.OFF_DATA
import org.thortracker.backend.Opl3PlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.Opl3PlayerImage.OFF_ORDERS
import org.thortracker.backend.Opl3PlayerImage.OFF_ROWS
import org.thortracker.backend.Opl3PlayerImage.OFF_SHAPES
import org.thortracker.backend.Opl3PlayerImage.OPERATOR_BYTES
import org.thortracker.backend.Opl3PlayerImage.RUN
import org.thortracker.backend.Opl3PlayerImage.SHAPE_SIZE
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.opl3.Opl3Instrument
import org.thortracker.system.opl3.Opl3Sound
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opl3.opl3

/**
 * Lowers an OPL3 song into the image Thor Tracker's OPL3 player (native `"opl3-fm"`, `opl3/opl3_player.c`) plays and
 * exports as VGM: the layout of docs/AUDIO_ENGINE.md "OPL3: Image layout" (constants: [Opl3PlayerImage], generated from
 * the C headers), byte for byte what the reference builder `tools/audio/opl3/opl3_song.py` (`build()`) writes for the
 * same song (`Opl3GoldenTest`).
 *
 * Orders are the song's arrangement ([Opl3System.arranged]); each order's rows = its pattern's length; each
 * (order, channel) is that track's rows encoded as the player reads them, and byte-identical encodings are stored once
 * in first-use order (orders, then channels). Instruments are written for every filled slot (the image's instrument
 * count is the highest), so the editor can audition any of them.
 */
object Opl3Lowering {
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
        Opl3PlayerImage.MAGIC.forEachIndexed { i, ch -> out[Opl3PlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[Opl3PlayerImage.H_LAYOUT] = Opl3PlayerImage.LAYOUT.toByte()
        out[Opl3PlayerImage.H_MULTISPEED] = sound.multispeed.toByte()
        out[Opl3PlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        out[Opl3PlayerImage.H_DEPTH] = sound.depthByte.toByte()
        u16(out, Opl3PlayerImage.H_ORDERS, song.orders.size)
        u16(out, Opl3PlayerImage.H_LOOP, song.loopOrder)
        out[Opl3PlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        out[Opl3PlayerImage.H_SHAPES] = sound.chords.size.toByte()
        sound.instruments.forEachIndexed { i, ins -> if (ins != null) record(ins).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i) }
        sound.chords.forEachIndexed { k, chord ->
            chord.intervals.forEachIndexed { i, v -> out[OFF_SHAPES + SHAPE_SIZE * k + i] = v.toByte() }
        }

        val blobs = HashMap<Blob, Int>()
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
        u16(out, Opl3PlayerImage.H_DATA_SIZE, size)
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
        r[Opl3PlayerImage.I_FLAGS] = (FLAG_DEFINED or (if (ins.fourOp) FLAG_FOUR_OP else 0)).toByte()
        r[Opl3PlayerImage.I_ALG] = ins.algorithm.toByte()
        r[Opl3PlayerImage.I_FEEDBACK] = ins.feedback.toByte()
        for (k in 0 until ins.operatorCount) {
            val op = ins.operators[k]
            val at = Opl3PlayerImage.I_OPS + OPERATOR_BYTES * k
            r[at] = op.reg20.toByte()
            r[at + 1] = op.reg40.toByte()
            r[at + 2] = op.reg60.toByte()
            r[at + 3] = op.reg80.toByte()
            r[at + 4] = op.regE0.toByte()
        }
        r[Opl3PlayerImage.I_SWEEP_START] = ins.sweep.start.toByte()
        r[Opl3PlayerImage.I_SWEEP_END] = ins.sweep.end.toByte()
        r[Opl3PlayerImage.I_SWEEP_SPEED] = ins.sweep.speed.toByte()
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
                val k = minOf(empty, MAX_RUN)
                out += (RUN or (k - 1)).toByte()
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
            out += ((if (hasNote) MASK_NOTE else 0) or (if (hasInstrument) MASK_INSTRUMENT else 0) or (effects shl MASK_EFFECT_SHIFT)).toByte()
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
