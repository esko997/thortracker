package org.thortracker.backend

import org.thortracker.backend.AyPlayerImage.CHANNELS
import org.thortracker.backend.AyPlayerImage.DATA_CAPACITY
import org.thortracker.backend.AyPlayerImage.ENV_HEADER
import org.thortracker.backend.AyPlayerImage.FLAG_AUTO_PERIOD
import org.thortracker.backend.AyPlayerImage.FLAG_DEFINED
import org.thortracker.backend.AyPlayerImage.FLAG_PITCH_RELATIVE
import org.thortracker.backend.AyPlayerImage.FLAG_SYNC
import org.thortracker.backend.AyPlayerImage.IMAGE_SIZE
import org.thortracker.backend.AyPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.AyPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.AyPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.AyPlayerImage.MASK_NOTE
import org.thortracker.backend.AyPlayerImage.MAX_RUN
import org.thortracker.backend.AyPlayerImage.NOTE_OFF
import org.thortracker.backend.AyPlayerImage.NO_ENVELOPE
import org.thortracker.backend.AyPlayerImage.OFF_DATA
import org.thortracker.backend.AyPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.AyPlayerImage.OFF_ORDERS
import org.thortracker.backend.AyPlayerImage.OFF_ROWS
import org.thortracker.backend.AyPlayerImage.RUN
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.ay.AyInstrument
import org.thortracker.system.ay.AySound
import org.thortracker.system.ay.ay
import org.thortracker.system.nes.NesEnvelope

/**
 * Lowers an AY-3-8910 / YM2149 song into the image Thor Tracker's AY player (native `"ay-zx128"`, `"ay-msx"`,
 * `"ay-atari-st"`, `"ay-cpc"`, `ay/ay_player.c`) plays and exports: the layout of docs/AUDIO_ENGINE.md "AY-3-8910 / YM2149: Image
 * layout" (constants: [AyPlayerImage], generated from the C headers), byte for byte what the reference builder
 * `tools/audio/ay/ay_song.py` (`build()`) writes for the same song (`AyGoldenTest`).
 *
 * As the Mega Drive's ([YmLowering]): orders are the song's arrangement (the variant's `arranged`); each order's rows =
 * its pattern's length; each (order, channel) is that track's rows encoded as the player reads them, byte-identical
 * encodings stored once in first-use order (orders, then channels). The envelopes follow the patterns in the data
 * region: filled slots ascending, each slot's volume, arpeggio, pitch, mixer and noise envelope, byte-identical
 * envelopes stored once. Every filled slot gets its record (the image's instrument count is the highest), so the editor
 * can audition any of them. The auto octave and the manual period are both kept in the model; only the active mode's is
 * written (the other byte stays 0), and sync only in auto mode.
 */
object AyLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's pattern and envelope data do not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The AY image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val sound = document.ay
        val song = sound.variant.system.arranged(document)
        AyPlayerImage.MAGIC.forEachIndexed { i, ch -> out[AyPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[AyPlayerImage.H_LAYOUT] = AyPlayerImage.LAYOUT.toByte()
        out[AyPlayerImage.H_VARIANT] = sound.variant.index.toByte()
        out[AyPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        u16(out, AyPlayerImage.H_ORDERS, song.orders.size)
        u16(out, AyPlayerImage.H_LOOP, song.loopOrder)
        out[AyPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()

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
        u16(out, AyPlayerImage.H_DATA_SIZE, data.size)
    }

    /** Bytes of pattern and envelope data [document] lowers to (after sharing identical tracks and envelopes). */
    fun dataSize(document: Song): Int {
        val song = document.ay.variant.system.arranged(document)
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
        for (ins in song.ay.instruments) {
            if (ins == null) continue
            for (env in envelopes(ins)) {
                if (env.values.isEmpty()) continue
                val blob = Blob(envelope(env))
                if (envelopes.add(blob)) size += blob.bytes.size
            }
        }
        return size
    }

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: AySound): Int = sound.instruments.indexOfLast { it != null } + 1

    /** [ins]'s five envelopes in record (and placement) order: volume, arpeggio, pitch, mixer, noise. */
    private fun envelopes(ins: AyInstrument) = listOf(ins.volume, ins.arpeggio, ins.pitch, ins.mixer, ins.noise)

    /**
     * The 32-byte instrument record; the envelope fields are the offsets [place] returns for each non-empty envelope's
     * bytes ([NO_ENVELOPE] for an empty one), placed in field order.
     */
    fun record(ins: AyInstrument, place: (NesEnvelope) -> Int): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        val env = ins.envelope
        var flags = FLAG_DEFINED
        if (ins.pitchRelative) flags = flags or FLAG_PITCH_RELATIVE
        if (env.auto) flags = flags or FLAG_AUTO_PERIOD
        if (env.imageSync) flags = flags or FLAG_SYNC
        r[AyPlayerImage.I_FLAGS] = flags.toByte()
        r[AyPlayerImage.I_SHAPE] = env.shape.toByte()
        r[AyPlayerImage.I_OCTAVE] = env.imageOctave.toByte()
        u16(r, AyPlayerImage.I_PERIOD, env.imagePeriod)
        fun field(at: Int, e: NesEnvelope) = u16(r, at, if (e.values.isEmpty()) NO_ENVELOPE else place(e))
        field(AyPlayerImage.I_ENV_VOLUME, ins.volume)
        field(AyPlayerImage.I_ENV_ARPEGGIO, ins.arpeggio)
        field(AyPlayerImage.I_ENV_PITCH, ins.pitch)
        field(AyPlayerImage.I_ENV_MIXER, ins.mixer)
        field(AyPlayerImage.I_ENV_NOISE, ins.noise)
        r[AyPlayerImage.I_SWEEP_START] = ins.sweep.start.toByte()
        r[AyPlayerImage.I_SWEEP_END] = ins.sweep.end.toByte()
        r[AyPlayerImage.I_SWEEP_SPEED] = ins.sweep.speed.toByte()
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
        "The song's pattern and envelope data need ${(dataSize(song) + 1023) / 1024} KB; the AY image holds " +
            "${DATA_CAPACITY / 1024} KB. Reuse patterns or shorten the song"

    /** The data region being filled: patterns first, then envelopes, each kind sharing identical bytes. */
    private class Data(private val out: ByteArray, private val song: Song) {
        var size = 0
            private set
        val patterns = Pool()
        private val envelopes = Pool()

        fun envelope(env: NesEnvelope): Int = envelopes.put(AyLowering.envelope(env))

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
