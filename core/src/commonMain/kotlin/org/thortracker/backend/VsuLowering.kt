package org.thortracker.backend

import org.thortracker.backend.VsuPlayerImage.CHANNELS
import org.thortracker.backend.VsuPlayerImage.DATA_CAPACITY
import org.thortracker.backend.VsuPlayerImage.IMAGE_SIZE
import org.thortracker.backend.VsuPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.VsuPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.VsuPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.VsuPlayerImage.MASK_NOTE
import org.thortracker.backend.VsuPlayerImage.MAX_RUN
import org.thortracker.backend.VsuPlayerImage.NOTE_OFF
import org.thortracker.backend.VsuPlayerImage.OFF_DATA
import org.thortracker.backend.VsuPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.VsuPlayerImage.OFF_MODULATION
import org.thortracker.backend.VsuPlayerImage.OFF_ORDERS
import org.thortracker.backend.VsuPlayerImage.OFF_ROWS
import org.thortracker.backend.VsuPlayerImage.OFF_WAVES
import org.thortracker.backend.VsuPlayerImage.RUN
import org.thortracker.backend.VsuPlayerImage.WAVE_SAMPLES
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.vsu.VsuInstrument
import org.thortracker.system.vsu.VsuSound
import org.thortracker.system.vsu.VsuSystem
import org.thortracker.system.vsu.vsu

/**
 * Lowers a Virtual Boy song into the image Thor Tracker's VSU player (native `"vb-vsu"`, `vsu/vsu_player.c`) plays and
 * exports as VGM: the layout of docs/AUDIO_ENGINE.md "Virtual Boy VSU: Image layout" (constants: [VsuPlayerImage],
 * kept by hand), exactly as "What the lowering writes" says.
 *
 * Orders are the song's arrangement ([VsuSystem.arranged]); each order's rows = its pattern's length; each
 * (order, lane) is that track's rows encoded as the player reads them, and byte-identical encodings are stored once in
 * first-use order (orders, then lanes). Instruments are written for every filled slot (the image's instrument count is
 * the highest), so the editor can audition any of them; the five waves and the modulation table are copied as they are.
 */
object VsuLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's pattern data does not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The VSU image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = VsuSystem.arranged(document)
        val sound = song.vsu
        VsuPlayerImage.MAGIC.forEachIndexed { i, ch -> out[VsuPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[VsuPlayerImage.H_LAYOUT] = VsuPlayerImage.LAYOUT.toByte()
        out[VsuPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        u16(out, VsuPlayerImage.H_ORDERS, song.orders.size)
        u16(out, VsuPlayerImage.H_LOOP, song.loopOrder)
        out[VsuPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        sound.instruments.forEachIndexed { i, ins -> if (ins != null) record(ins).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i) }
        sound.waves.forEachIndexed { w, samples -> samples.forEachIndexed { i, v -> out[OFF_WAVES + WAVE_SAMPLES * w + i] = v.toByte() } }
        sound.modulation.forEachIndexed { i, v -> out[OFF_MODULATION + i] = v.toByte() }

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
        u16(out, VsuPlayerImage.H_DATA_SIZE, size)
    }

    /** Bytes of pattern data [document] lowers to (after sharing identical tracks). */
    fun dataSize(document: Song): Int {
        val song = VsuSystem.arranged(document)
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
        "The song's pattern data needs ${(dataSize(song) + 1023) / 1024} KB; the VSU image holds ${DATA_CAPACITY / 1024} KB. " +
            "Reuse patterns or shorten the song"

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: VsuSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The 16-byte instrument record: flags, level, interval, length, wave, noise tap, frequency modification, S5SWP,
     * the software sweep (start and end written 0 when its speed is 0), bytes 11..15 zero.
     */
    fun record(ins: VsuInstrument): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        r[VsuPlayerImage.I_FLAGS] = ins.flagsByte.toByte()
        r[VsuPlayerImage.I_LEVEL] = ins.level.toByte()
        r[VsuPlayerImage.I_INTERVAL] = ins.interval.toByte()
        r[VsuPlayerImage.I_LENGTH] = ins.length.toByte()
        r[VsuPlayerImage.I_WAVE] = ins.wave.toByte()
        r[VsuPlayerImage.I_TAP] = ins.noiseTap.toByte()
        r[VsuPlayerImage.I_FREQ_MOD] = ins.freqMod.flagsByte.toByte()
        r[VsuPlayerImage.I_SWP] = ins.freqMod.swpByte.toByte()
        if (ins.sweep.active) {
            r[VsuPlayerImage.I_SWEEP_START] = ins.sweep.start.toByte()
            r[VsuPlayerImage.I_SWEEP_END] = ins.sweep.end.toByte()
        }
        r[VsuPlayerImage.I_SWEEP_SPEED] = ins.sweep.speed.toByte()
        return r
    }

    /**
     * [rows] rows of [track] as the player reads them: empty rows in runs (`0x80 | k` = k + 1 rows), else a mask (bit 0
     * note, bit 1 instrument, bits 2-3 effect count), the note (`0xFE` = OFF), the slot and each non-blank effect
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
