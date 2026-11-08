package org.thortracker.backend

import org.thortracker.backend.YmPlayerImage.CHANNELS
import org.thortracker.backend.YmPlayerImage.DATA_CAPACITY
import org.thortracker.backend.YmPlayerImage.ENV_HEADER
import org.thortracker.backend.YmPlayerImage.FLAG_DEFINED
import org.thortracker.backend.YmPlayerImage.FLAG_PSG
import org.thortracker.backend.YmPlayerImage.IMAGE_SIZE
import org.thortracker.backend.YmPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.YmPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.YmPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.YmPlayerImage.MASK_NOTE
import org.thortracker.backend.YmPlayerImage.MAX_RUN
import org.thortracker.backend.YmPlayerImage.NOTE_OFF
import org.thortracker.backend.YmPlayerImage.NO_ENVELOPE
import org.thortracker.backend.YmPlayerImage.OFF_DATA
import org.thortracker.backend.YmPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.YmPlayerImage.OFF_ORDERS
import org.thortracker.backend.YmPlayerImage.OFF_ROWS
import org.thortracker.backend.YmPlayerImage.OP_BYTES
import org.thortracker.backend.YmPlayerImage.RUN
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmInstrument
import org.thortracker.system.ym2612.YmPsgInstrument
import org.thortracker.system.ym2612.YmSound
import org.thortracker.system.ym2612.YmSystem
import org.thortracker.system.ym2612.ym

/**
 * Lowers a Mega Drive song into the image Thor Tracker's YM2612 player (native `"ym2612-fm"`, `ym/ym_player.c`) plays
 * through the XGM driver and exports: the layout of docs/AUDIO_ENGINE.md "YM2612: Image layout" (constants:
 * [YmPlayerImage], generated from the C headers), byte for byte what the reference builder `tools/audio/ym/ym_song.py`
 * (`build()`) writes for the same song (`YmGoldenTest`).
 *
 * Orders are the song's arrangement ([YmSystem.arranged]); each order's rows = its pattern's length; each
 * (order, channel) is that track's rows encoded as the player reads them, byte-identical encodings stored once in
 * first-use order (orders, then channels). The PSG envelopes follow the patterns in the data region: slots ascending,
 * each slot's volume, arpeggio and pitch envelope, byte-identical envelopes stored once. Instruments are written for
 * every filled slot (the image's instrument count is the highest), so the editor can audition any of them.
 */
object YmLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's pattern and envelope data do not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The Mega Drive image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = YmSystem.arranged(document)
        val sound = song.ym
        YmPlayerImage.MAGIC.forEachIndexed { i, ch -> out[YmPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[YmPlayerImage.H_LAYOUT] = YmPlayerImage.LAYOUT.toByte()
        out[YmPlayerImage.H_REGION] = (if (sound.pal) YmPlayerImage.PAL else YmPlayerImage.NTSC).toByte()
        out[YmPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        out[YmPlayerImage.H_LFO] = sound.lfo.toByte()
        u16(out, YmPlayerImage.H_ORDERS, song.orders.size)
        u16(out, YmPlayerImage.H_LOOP, song.loopOrder)
        out[YmPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()

        val data = Data(out, song)
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            out[OFF_ROWS + o] = (pattern.length and 0xFF).toByte()
            for (c in 0 until CHANNELS) {
                u16(out, OFF_ORDERS + 2 * (CHANNELS * o + c), data.patterns.put(encode(pattern.tracks[c], pattern.length)))
            }
        }
        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(ins, data::envelope).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        u16(out, YmPlayerImage.H_DATA_SIZE, data.size)
    }

    /** Bytes of pattern and envelope data [document] lowers to (after sharing identical tracks and envelopes). */
    fun dataSize(document: Song): Int {
        val song = YmSystem.arranged(document)
        val patterns = HashSet<Blob>()
        val envelopes = HashSet<Blob>()
        var size = 0
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (c in 0 until CHANNELS) {
                val blob = Blob(encode(pattern.tracks[c], pattern.length))
                if (patterns.add(blob)) size += blob.bytes.size
            }
        }
        for (ins in song.ym.instruments) {
            if (ins !is YmPsgInstrument) continue
            for (env in listOf(ins.volume, ins.arpeggio, ins.pitch)) {
                if (env.values.isEmpty()) continue
                val blob = Blob(envelope(env))
                if (envelopes.add(blob)) size += blob.bytes.size
            }
        }
        return size
    }

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: YmSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The 40-byte instrument record; a PSG record's envelope fields are the offsets [place] returns for each
     * non-empty envelope's bytes ([NO_ENVELOPE] for an empty one).
     */
    fun record(ins: YmInstrument, place: (NesEnvelope) -> Int): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        when (ins) {
            is YmFmInstrument -> {
                r[YmPlayerImage.I_FLAGS] = FLAG_DEFINED.toByte()
                r[YmPlayerImage.I_ALG] = ins.algorithm.toByte()
                r[YmPlayerImage.I_FEEDBACK] = ins.feedback.toByte()
                r[YmPlayerImage.I_AMS_FMS] = ins.amsFms.toByte()
                ins.operators.forEachIndexed { k, op ->
                    val at = YmPlayerImage.I_OPS + OP_BYTES * k
                    intArrayOf(op.reg30, op.reg40, op.reg50, op.reg60, op.reg70, op.reg80, op.reg90)
                        .forEachIndexed { i, v -> r[at + i] = v.toByte() }
                }
            }
            is YmPsgInstrument -> {
                r[YmPlayerImage.I_FLAGS] = (FLAG_DEFINED or FLAG_PSG).toByte()
                r[YmPlayerImage.I_PSG_NOISE] = ins.noise.toByte()
                r[YmPlayerImage.I_PSG_FLAGS] = (if (ins.pitchRelative) YmPlayerImage.PSG_PITCH_RELATIVE else 0).toByte()
                fun env(at: Int, e: NesEnvelope) = u16(r, at, if (e.values.isEmpty()) NO_ENVELOPE else place(e))
                env(YmPlayerImage.I_ENV_VOLUME, ins.volume)
                env(YmPlayerImage.I_ENV_ARPEGGIO, ins.arpeggio)
                env(YmPlayerImage.I_ENV_PITCH, ins.pitch)
            }
        }
        r[YmPlayerImage.I_SWEEP_START] = ins.sweep.start.toByte()
        r[YmPlayerImage.I_SWEEP_END] = ins.sweep.end.toByte()
        r[YmPlayerImage.I_SWEEP_SPEED] = ins.sweep.speed.toByte()
        return r
    }

    /** A non-empty envelope's bytes: u16 length, u16 loop and u16 release ([NO_ENVELOPE] = none), then the values (s8). */
    fun envelope(env: NesEnvelope): ByteArray {
        val r = ByteArray(ENV_HEADER + env.values.size)
        u16(r, 0, env.values.size)
        u16(r, 2, if (env.loop < 0) NO_ENVELOPE else env.loop)
        u16(r, 4, if (env.release < 0) NO_ENVELOPE else env.release)
        env.values.forEachIndexed { i, v -> r[ENV_HEADER + i] = v.toByte() }
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

    private fun dataTooLarge(song: Song): String =
        "The song's pattern and envelope data need ${(dataSize(song) + 1023) / 1024} KB; the Mega Drive image holds " +
            "${DATA_CAPACITY / 1024} KB. Reuse patterns or shorten the song"

    /** The data region being filled: patterns first, then envelopes, each kind sharing identical bytes. */
    private class Data(private val out: ByteArray, private val song: Song) {
        var size = 0
            private set
        val patterns = Pool()
        private val envelopes = Pool()

        fun envelope(env: NesEnvelope): Int = envelopes.put(YmLowering.envelope(env))

        inner class Pool {
            private val at = HashMap<Blob, Int>()

            /** [bytes]' offset in the data region, appended when not stored yet. */
            fun put(bytes: ByteArray): Int = at.getOrPut(Blob(bytes)) {
                val start = size
                require(start + bytes.size <= DATA_CAPACITY) { dataTooLarge(song) }
                bytes.copyInto(out, OFF_DATA + start)
                size += bytes.size
                start
            }
        }
    }

    /** Encoded bytes with content equality, for sharing. */
    private class Blob(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Blob && other.bytes.contentEquals(bytes)
    }
}
