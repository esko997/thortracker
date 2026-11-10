package org.thortracker.backend

import org.thortracker.backend.TiaPlayerImage.CHANNELS
import org.thortracker.backend.TiaPlayerImage.DATA_CAPACITY
import org.thortracker.backend.TiaPlayerImage.ENVELOPE_BYTES
import org.thortracker.backend.TiaPlayerImage.ENVELOPE_COUNT
import org.thortracker.backend.TiaPlayerImage.ENV_ARPEGGIO
import org.thortracker.backend.TiaPlayerImage.ENV_NONE
import org.thortracker.backend.TiaPlayerImage.ENV_PITCH
import org.thortracker.backend.TiaPlayerImage.ENV_VOLUME
import org.thortracker.backend.TiaPlayerImage.ENV_WAVEFORM
import org.thortracker.backend.TiaPlayerImage.FLAG_DEFINED
import org.thortracker.backend.TiaPlayerImage.FLAG_RAW
import org.thortracker.backend.TiaPlayerImage.FLAG_RELATIVE
import org.thortracker.backend.TiaPlayerImage.IMAGE_SIZE
import org.thortracker.backend.TiaPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.TiaPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.TiaPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.TiaPlayerImage.MASK_NOTE
import org.thortracker.backend.TiaPlayerImage.MAX_RUN
import org.thortracker.backend.TiaPlayerImage.NOTE_OFF
import org.thortracker.backend.TiaPlayerImage.OFF_DATA
import org.thortracker.backend.TiaPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.TiaPlayerImage.OFF_ORDERS
import org.thortracker.backend.TiaPlayerImage.OFF_ROWS
import org.thortracker.backend.TiaPlayerImage.RUN
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.tia.TiaEnvelope
import org.thortracker.system.tia.TiaInstrument
import org.thortracker.system.tia.TiaSound
import org.thortracker.system.tia.TiaSystem
import org.thortracker.system.tia.tia
import org.thortracker.system.tia.tiaSystem

/**
 * Lowers an Atari 2600 song into the image Thor Tracker's TIA player (native `"atari2600-tia-ntsc"` and
 * `"atari2600-tia-pal"`, `tia/tia_player.c`) plays: the layout of docs/AUDIO_ENGINE.md "Atari 2600: Image layout"
 * (constants: [TiaPlayerImage], generated from the C headers), byte for byte what the reference builder
 * `tools/audio/tia/tia_song.py` (`build()`) writes for the same song (`TiaGoldenTest`).
 *
 * Orders are the song's arrangement ([TiaSystem.arranged]); each order's rows = its pattern's length. The header names
 * the song's region (the loading engine must be that region's). The data region holds, in this order: each (order,
 * channel)'s rows encoded as the player reads them, byte-identical encodings stored once in first-use order (orders,
 * then channels); then each filled instrument's envelopes in slot order (volume, arpeggio, pitch, waveform;
 * byte-identical value lists stored once, first use). Every filled slot is written as its own record (the image's
 * instrument count is the highest), so the editor can audition any of them and a live edit of one slot never changes
 * another, even when two slots are byte-identical.
 */
object TiaLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's data does not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The Atari 2600 image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = document.tiaSystem.arranged(document)
        val sound = song.tia
        val layout = Layout(song)
        require(layout.fits) { layout.problem() }

        TiaPlayerImage.MAGIC.forEachIndexed { i, ch -> out[TiaPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[TiaPlayerImage.H_LAYOUT] = TiaPlayerImage.LAYOUT.toByte()
        out[TiaPlayerImage.H_REGION] = sound.region.code.toByte()
        out[TiaPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        out[TiaPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        u16(out, TiaPlayerImage.H_ORDERS, song.orders.size)
        u16(out, TiaPlayerImage.H_LOOP, song.loopOrder)
        u32(out, TiaPlayerImage.H_DATA_SIZE, layout.size)

        song.orders.forEachIndexed { o, p -> out[OFF_ROWS + o] = (song.patterns[p].length and 0xFF).toByte() }
        layout.tracks.forEachIndexed { i, at -> u16(out, OFF_ORDERS + 2 * i, at) }
        for ((bytes, at) in layout.blobs) bytes.copyInto(out, OFF_DATA + at)

        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(ins, layout.envelopes[i]!!).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
    }

    /** Bytes of the data region [document] lowers to: patterns and envelopes after sharing identical ones. */
    fun dataSize(document: Song): Int = Layout(document.tiaSystem.arranged(document)).size

    /** Why [document]'s data does not fit the image's data region, or null when it fits. */
    fun sizeProblem(document: Song): String? = Layout(document.tiaSystem.arranged(document)).let { if (it.fits) null else it.problem() }

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: TiaSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /** The envelopes of [ins] in record order (volume, arpeggio, pitch, waveform); null for one without steps. */
    fun lowered(ins: TiaInstrument): List<TiaEnvelope?> {
        val envelopes = arrayOfNulls<TiaEnvelope>(ENVELOPE_COUNT)
        envelopes[ENV_VOLUME] = ins.volume
        envelopes[ENV_ARPEGGIO] = ins.arpeggio
        envelopes[ENV_PITCH] = ins.pitch
        envelopes[ENV_WAVEFORM] = ins.waveforms
        return envelopes.map { env -> env?.takeUnless { it.isEmpty } }
    }

    /** The bytes of envelope [env] in the data region: one per step (s8 for arpeggio and pitch). */
    fun envelopeBytes(env: TiaEnvelope): ByteArray = ByteArray(env.values.size) { env.values[it].toByte() }

    /**
     * The 32-byte instrument record of [ins] with its envelopes at data offsets [envelopeOffsets] (record order; any
     * value for an envelope without steps): flags (defined, raw pitch, relative pitch envelope), the waveform (AUDC),
     * then per envelope offset, length, loop and release (`$FF` = none; an envelope without steps: offset 0, length 0).
     */
    fun record(ins: TiaInstrument, envelopeOffsets: IntArray): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        r[TiaPlayerImage.I_FLAGS] = (FLAG_DEFINED or (if (ins.raw) FLAG_RAW else 0) or (if (ins.relativePitch) FLAG_RELATIVE else 0)).toByte()
        r[TiaPlayerImage.I_WAVEFORM] = ins.waveform.toByte()
        lowered(ins).forEachIndexed { k, env ->
            val at = TiaPlayerImage.I_ENVELOPES + ENVELOPE_BYTES * k
            if (env == null) {
                r[at + 3] = ENV_NONE.toByte()
                r[at + 4] = ENV_NONE.toByte()
            } else {
                u16(r, at, envelopeOffsets[k])
                r[at + 2] = env.values.size.toByte()
                r[at + 3] = (if (env.loop < 0) ENV_NONE else env.loop).toByte()
                r[at + 4] = (if (env.release < 0) ENV_NONE else env.release).toByte()
            }
        }
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
            require(cell.more.size < TiaPlayerImage.MAX_EFFECTS) {
                "TIA row $r has ${cell.more.size + 1} effect columns; at most ${TiaPlayerImage.MAX_EFFECTS} are supported"
            }
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

    /**
     * Where everything of an arranged [song] goes in the data region: [tracks] = the offset of each (order, channel) at
     * index 2 o + c, [envelopes] = per slot the offsets of its four envelopes, [blobs] = the bytes to copy and their
     * offsets, [size] = the region's used bytes.
     */
    private class Layout(song: Song) {
        val tracks = IntArray(song.orders.size * CHANNELS)
        val envelopes = arrayOfNulls<IntArray>(TiaSystem.SLOTS)
        val blobs = ArrayList<Pair<ByteArray, Int>>()
        var size = 0
            private set

        private val patternSize: Int

        init {
            val sound = song.tia
            val patterns = HashMap<Blob, Int>()
            song.orders.forEachIndexed { o, p ->
                val pattern = song.patterns[p]
                for (c in 0 until CHANNELS) tracks[CHANNELS * o + c] = place(patterns, encode(pattern.tracks[c], pattern.length))
            }
            patternSize = size
            val shared = HashMap<Blob, Int>()
            sound.instruments.forEachIndexed { i, ins ->
                if (ins == null) return@forEachIndexed
                val at = IntArray(ENVELOPE_COUNT)
                lowered(ins).forEachIndexed { k, env -> if (env != null) at[k] = place(shared, envelopeBytes(env)) }
                envelopes[i] = at
            }
        }

        val fits: Boolean get() = size <= DATA_CAPACITY

        fun problem(): String =
            "The song's data needs ${kb(size)} (patterns ${kb(patternSize)}, envelopes ${kb(size - patternSize)}); the Atari 2600 " +
                "image holds ${kb(DATA_CAPACITY)} ($DATA_CAPACITY bytes). Reuse patterns, shorten the song or its envelopes"

        /** [bytes]' offset: an identical earlier blob's in [seen], else appended. */
        private fun place(seen: HashMap<Blob, Int>, bytes: ByteArray): Int {
            val blob = Blob(bytes)
            seen[blob]?.let { return it }
            val at = size
            seen[blob] = at
            blobs += bytes to at
            size += bytes.size
            return at
        }

        private fun kb(bytes: Int) = "${(bytes + 1023) / 1024} KB"
    }

    private fun u16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun u32(out: ByteArray, at: Int, value: Int) {
        u16(out, at, value and 0xFFFF)
        u16(out, at + 2, value ushr 16)
    }

    /** Encoded bytes with content equality, for sharing. */
    private class Blob(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Blob && other.bytes.contentEquals(bytes)
    }
}
