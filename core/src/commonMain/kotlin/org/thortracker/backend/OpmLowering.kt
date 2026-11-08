package org.thortracker.backend

import org.thortracker.backend.OpmPlayerImage.CHANNELS
import org.thortracker.backend.OpmPlayerImage.DATA_CAPACITY
import org.thortracker.backend.OpmPlayerImage.FLAG_DEFINED
import org.thortracker.backend.OpmPlayerImage.FLAG_NOISE
import org.thortracker.backend.OpmPlayerImage.IMAGE_SIZE
import org.thortracker.backend.OpmPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.OpmPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.OpmPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.OpmPlayerImage.MASK_NOTE
import org.thortracker.backend.OpmPlayerImage.MAX_RUN
import org.thortracker.backend.OpmPlayerImage.NOTE_OFF
import org.thortracker.backend.OpmPlayerImage.OFF_DATA
import org.thortracker.backend.OpmPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.OpmPlayerImage.OFF_ORDERS
import org.thortracker.backend.OpmPlayerImage.OFF_ROWS
import org.thortracker.backend.OpmPlayerImage.OPERATOR_BYTES
import org.thortracker.backend.OpmPlayerImage.RUN
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.opm.OpmInstrument
import org.thortracker.system.opm.OpmSound
import org.thortracker.system.opm.OpmSystem
import org.thortracker.system.opm.opm

/**
 * Lowers an OPM song into the image Thor Tracker's OPM player (native `"ym2151-opm"`, `opm/opm_player.c`) plays and
 * exports as VGM: the layout of docs/AUDIO_ENGINE.md "OPM: Image layout" (constants: [OpmPlayerImage], generated from
 * the C headers), byte for byte what the reference builder `tools/audio/opm/opm_song.py` (`build()`) writes for the
 * same song (`OpmGoldenTest`).
 *
 * Orders are the song's arrangement ([OpmSystem.arranged]); each order's rows = its pattern's length; each
 * (order, channel) is that track's rows encoded as the player reads them, and byte-identical encodings are stored once
 * in first-use order (orders, then channels). Instruments are written for every filled slot (the image's instrument
 * count is the highest), so the editor can audition any of them. The header carries the chip's shared state at the
 * song start: LFO rate, waveform, AMD, PMD, CSM and timer A.
 */
object OpmLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's pattern data does not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The OPM image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = OpmSystem.arranged(document)
        val sound = song.opm
        OpmPlayerImage.MAGIC.forEachIndexed { i, ch -> out[OpmPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[OpmPlayerImage.H_LAYOUT] = OpmPlayerImage.LAYOUT.toByte()
        out[OpmPlayerImage.H_MULTISPEED] = sound.multispeed.toByte()
        out[OpmPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        out[OpmPlayerImage.H_LFO_WAVE] = sound.lfoWave.toByte()
        u16(out, OpmPlayerImage.H_ORDERS, song.orders.size)
        u16(out, OpmPlayerImage.H_LOOP, song.loopOrder)
        out[OpmPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        out[OpmPlayerImage.H_CSM] = (if (sound.csm) 1 else 0).toByte()
        out[OpmPlayerImage.H_LFO_RATE] = sound.lfoRate.toByte()
        out[OpmPlayerImage.H_AMD] = sound.amd.toByte()
        out[OpmPlayerImage.H_PMD] = sound.pmd.toByte()
        u16(out, OpmPlayerImage.H_TIMER_A, sound.timerA)
        sound.instruments.forEachIndexed { i, ins -> if (ins != null) record(ins).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i) }

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
        u16(out, OpmPlayerImage.H_DATA_SIZE, size)
    }

    /** Bytes of pattern data [document] lowers to (after sharing identical tracks). */
    fun dataSize(document: Song): Int {
        val song = OpmSystem.arranged(document)
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
        "The song's pattern data needs ${(dataSize(song) + 1023) / 1024} KB; the OPM image holds ${DATA_CAPACITY / 1024} KB. " +
            "Reuse patterns or shorten the song"

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: OpmSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The 32-byte instrument record: flags (defined, noise), connection ($20 without pan), sensitivity ($38), the noise
     * frequency (0 without noise), six register bytes per operator in operator order 1..4 (M1, C1, M2, C2) and the
     * drum sweep.
     */
    fun record(ins: OpmInstrument): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        r[OpmPlayerImage.I_FLAGS] = (FLAG_DEFINED or (if (ins.noise) FLAG_NOISE else 0)).toByte()
        r[OpmPlayerImage.I_CONNECT] = ins.connect.toByte()
        r[OpmPlayerImage.I_SENS] = ins.sens.toByte()
        r[OpmPlayerImage.I_NOISE] = (if (ins.noise) ins.noiseFrequency else 0).toByte()
        for (k in 0 until 4) {
            val op = ins.operators[k]
            val at = OpmPlayerImage.I_OPS + OPERATOR_BYTES * k
            r[at] = op.reg40.toByte()
            r[at + 1] = op.reg60.toByte()
            r[at + 2] = op.reg80.toByte()
            r[at + 3] = op.regA0.toByte()
            r[at + 4] = op.regC0.toByte()
            r[at + 5] = op.regE0.toByte()
        }
        r[OpmPlayerImage.I_SWEEP_START] = ins.sweep.start.toByte()
        r[OpmPlayerImage.I_SWEEP_END] = ins.sweep.end.toByte()
        r[OpmPlayerImage.I_SWEEP_SPEED] = ins.sweep.speed.toByte()
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
