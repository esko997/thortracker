package org.thortracker.backend

import org.thortracker.backend.CbmImage.DATA_CAPACITY
import org.thortracker.backend.CbmImage.ENVELOPE_BYTES
import org.thortracker.backend.CbmImage.ENVELOPE_COUNT
import org.thortracker.backend.CbmImage.ENV_ARPEGGIO
import org.thortracker.backend.CbmImage.ENV_NONE
import org.thortracker.backend.CbmImage.ENV_PITCH
import org.thortracker.backend.CbmImage.ENV_VOLUME
import org.thortracker.backend.CbmImage.ENV_WAVE
import org.thortracker.backend.CbmImage.FLAG_DEFINED
import org.thortracker.backend.CbmImage.FLAG_RELATIVE
import org.thortracker.backend.CbmImage.IMAGE_SIZE
import org.thortracker.backend.CbmImage.INSTRUMENT_SIZE
import org.thortracker.backend.CbmImage.KIND_SAMPLE
import org.thortracker.backend.CbmImage.KIND_TONE
import org.thortracker.backend.CbmImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.CbmImage.MASK_INSTRUMENT
import org.thortracker.backend.CbmImage.MASK_NOTE
import org.thortracker.backend.CbmImage.MAX_CHANNELS
import org.thortracker.backend.CbmImage.MAX_RUN
import org.thortracker.backend.CbmImage.NOTE_OFF
import org.thortracker.backend.CbmImage.OFF_DATA
import org.thortracker.backend.CbmImage.OFF_INSTRUMENTS
import org.thortracker.backend.CbmImage.OFF_ORDERS
import org.thortracker.backend.CbmImage.OFF_ROWS
import org.thortracker.backend.CbmImage.OFF_SAMPLES
import org.thortracker.backend.CbmImage.RUN
import org.thortracker.backend.CbmImage.SAMPLE_ENTRY_SIZE
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.cbm.CbmChip
import org.thortracker.system.cbm.CbmEnvelope
import org.thortracker.system.cbm.CbmInstrument
import org.thortracker.system.cbm.CbmKind
import org.thortracker.system.cbm.CbmSound
import org.thortracker.system.cbm.CbmSystem
import org.thortracker.system.cbm.cbm

/**
 * Lowers a VIC-20 or C16/Plus/4 song into the image Thor Tracker's own player plays (native `"vic20-vic"` /
 * `"c16-ted"`, `cbm/cbm_player.c`): docs/AUDIO_ENGINE.md "VIC-20 and C16/Plus/4: What the lowering writes", constants
 * [CbmImage].
 *
 * Orders are the song's arrangement ([CbmSystem.arranged]); each order's rows = its pattern's length; the order table
 * has four channel entries per order (the TED's fourth stays 0). The data region holds, in this order: each (order,
 * channel)'s rows encoded as the player reads them, byte-identical encodings stored once in first-use order (orders,
 * then channels); each filled slot's envelopes in slot order (volume, arpeggio, pitch, wave; byte-identical envelopes
 * stored once, first use); then the samples in sample order (identical frames stored once). Pattern and envelope
 * offsets are u16, sample offsets u32. Every filled slot is written (the instrument count is the highest) and every
 * sample, so the editor can audition any of them. The header carries the machine, multispeed and the chip's shared
 * state at the song start: master volume and the DAC timer.
 */
object CbmLowering {
    /** The largest offset a pattern or envelope reference holds (u16). */
    const val MAX_SHORT_OFFSET = 0xFFFF

    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's data does not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The VIC-20 / C16 image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = CbmSystem.of(document).arranged(document)
        val sound = song.cbm
        val layout = Layout(song)
        require(layout.fits) { layout.problem() }

        CbmImage.MAGIC.forEachIndexed { i, ch -> out[CbmImage.H_MAGIC + i] = ch.code.toByte() }
        out[CbmImage.H_LAYOUT] = CbmImage.LAYOUT.toByte()
        out[CbmImage.H_CHIP] = sound.chip.code.toByte()
        out[CbmImage.H_VIDEO] = sound.video.code.toByte()
        out[CbmImage.H_MULTISPEED] = sound.multispeed.toByte()
        out[CbmImage.H_SPEED] = song.ticksPerRow.toByte()
        out[CbmImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        u16(out, CbmImage.H_ORDERS, song.orders.size)
        u16(out, CbmImage.H_LOOP, song.loopOrder)
        out[CbmImage.H_SAMPLES] = (if (sound.chip == CbmChip.TED) sound.samples.size else 0).toByte()
        out[CbmImage.H_VOLUME] = sound.volume.toByte()
        out[CbmImage.H_TIMER] = (if (sound.chip == CbmChip.TED) sound.dacTimer else 0).toByte()
        u32(out, CbmImage.H_DATA_SIZE, layout.size)

        song.orders.forEachIndexed { o, p -> out[OFF_ROWS + o] = (song.patterns[p].length and 0xFF).toByte() }
        val channels = sound.chip.channels
        song.orders.indices.forEach { o ->
            for (c in 0 until channels) u16(out, OFF_ORDERS + 2 * (MAX_CHANNELS * o + c), layout.tracks[channels * o + c])
        }
        for ((bytes, at) in layout.blobs) bytes.copyInto(out, OFF_DATA + at)

        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(sound.chip, ins, layout.envelopes[i]!!).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        if (sound.chip == CbmChip.TED) sound.samples.forEachIndexed { s, sample ->
            u32(out, OFF_SAMPLES + SAMPLE_ENTRY_SIZE * s, layout.samples[s])
            u32(out, OFF_SAMPLES + SAMPLE_ENTRY_SIZE * s + 4, sample.data.size)
        }
    }

    /** Bytes of the data region [document] lowers to: patterns, envelopes and samples after sharing identical ones. */
    fun dataSize(document: Song): Int = Layout(CbmSystem.of(document).arranged(document)).size

    /** Why [document]'s data does not fit the image (the capacity, or patterns and envelopes past the u16 offsets), or null. */
    fun sizeProblem(document: Song): String? = Layout(CbmSystem.of(document).arranged(document)).let { if (it.fits) null else it.problem() }

    /** Bytes of [document]'s pattern and envelope data (they share the first 64 KB of the data region). */
    fun shortDataSize(document: Song): Int = Layout(CbmSystem.of(document).arranged(document)).sampleStart

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: CbmSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The envelopes of [ins] the lowering writes, in record order (volume, arpeggio, pitch, wave): volume and wave on
     * tone sounds only (sample sounds have none: the DAC ignores the master volume); null for "none" (no steps).
     */
    fun lowered(ins: CbmInstrument): List<CbmEnvelope?> {
        val envelopes = arrayOfNulls<CbmEnvelope>(ENVELOPE_COUNT)
        envelopes[ENV_VOLUME] = if (ins.kind == CbmKind.TONE) ins.volume else null
        envelopes[ENV_ARPEGGIO] = ins.arpeggio
        envelopes[ENV_PITCH] = ins.pitch
        envelopes[ENV_WAVE] = if (ins.kind == CbmKind.TONE) ins.waves else null
        return envelopes.map { env -> env?.takeUnless { it.isEmpty } }
    }

    /** The bytes of envelope [env] in the data region: one per step (s8 for arpeggio and pitch). */
    fun envelopeBytes(env: CbmEnvelope): ByteArray = ByteArray(env.values.size) { env.values[it].toByte() }

    /**
     * The 32-byte record of [ins] on [chip] with its envelopes at [envelopeOffsets] (record order): flags (defined,
     * kind, relative pitch), the tone's wave (VIC: always square), the sample's index, root, loop flag and loop start
     * (zero for tones and without loop), then per envelope offset, length, loop and release (`$FF` = none).
     */
    fun record(chip: CbmChip, ins: CbmInstrument, envelopeOffsets: IntArray): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        val kind = if (ins.kind == CbmKind.SAMPLE) KIND_SAMPLE else KIND_TONE
        r[CbmImage.I_FLAGS] = (FLAG_DEFINED or kind or (if (ins.pitchRelative) FLAG_RELATIVE else 0)).toByte()
        if (ins.kind == CbmKind.TONE) r[CbmImage.I_WAVE] = (if (chip == CbmChip.VIC) CbmImage.WAVE_SQUARE else ins.wave).toByte()
        if (ins.kind == CbmKind.SAMPLE) {
            r[CbmImage.I_SAMPLE] = ins.sample.toByte()
            r[CbmImage.I_ROOT] = ins.root.toByte()
            if (ins.loop) {
                r[CbmImage.I_LOOP] = 1
                u16(r, CbmImage.I_LOOP_START, ins.loopStart)
            }
        }
        lowered(ins).forEachIndexed { k, env ->
            val at = CbmImage.I_ENVELOPES + ENVELOPE_BYTES * k
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
     * [rows] rows of [track] as the player reads them: empty rows in runs (`0x80 | k` = k + 1 rows, at most 128 per
     * byte), else a mask (bit 0 note, bit 1 instrument, bits 2-3 effect count), the note (`0xFE` = OFF), the slot and
     * each non-blank effect column's code and parameter in column order.
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

    /**
     * Where everything of an arranged [song] goes in the data region: [tracks] = the offset of each (order, channel)
     * at index channels · o + c, [envelopes] = per slot its four envelopes' offsets, [samples] = each sample's offset,
     * [blobs] = the bytes to copy and their offsets, [size] = the region's used bytes.
     */
    private class Layout(song: Song) {
        val tracks: IntArray
        val envelopes = arrayOfNulls<IntArray>(CbmSystem.SLOTS)
        val samples: IntArray
        val blobs = ArrayList<Pair<ByteArray, Int>>()
        var size = 0
            private set

        /** The first byte after the patterns and envelopes (where the samples start). */
        val sampleStart: Int

        /** The largest pattern or envelope start offset written. */
        private var lastShort = 0

        private val patternSize: Int
        private val envelopeSize: Int

        init {
            val sound = song.cbm
            val channels = sound.chip.channels
            tracks = IntArray(song.orders.size * channels)
            val patterns = HashMap<Blob, Int>()
            song.orders.forEachIndexed { o, p ->
                val pattern = song.patterns[p]
                for (c in 0 until channels) {
                    tracks[channels * o + c] = place(patterns, encode(pattern.tracks[c], pattern.length), short = true)
                }
            }
            patternSize = size
            val shared = HashMap<Blob, Int>()
            sound.instruments.forEachIndexed { i, ins ->
                if (ins == null) return@forEachIndexed
                val at = IntArray(ENVELOPE_COUNT)
                lowered(ins).forEachIndexed { k, env -> if (env != null) at[k] = place(shared, envelopeBytes(env), short = true) }
                envelopes[i] = at
            }
            envelopeSize = size - patternSize
            sampleStart = size
            val data = HashMap<Blob, Int>()
            val lowered = if (sound.chip == CbmChip.TED) sound.samples else emptyList()
            samples = IntArray(lowered.size) { s ->
                val sample = lowered[s]
                place(data, ByteArray(sample.data.size) { sample.data[it].toByte() }, short = false)
            }
        }

        val fits: Boolean get() = size <= DATA_CAPACITY && lastShort <= MAX_SHORT_OFFSET

        fun problem(): String = if (lastShort > MAX_SHORT_OFFSET) {
            "The song's patterns (${kb(patternSize)}) and envelopes (${kb(envelopeSize)}) pass the 64 KB their offsets " +
                "reach. Reuse patterns or shorten the song"
        } else {
            "The song's data needs ${kb(size)} (patterns ${kb(patternSize)}, envelopes ${kb(envelopeSize)}, samples " +
                "${kb(size - sampleStart)}); the player holds ${kb(DATA_CAPACITY)}. Shorten or remove samples, or reuse patterns"
        }

        /** [bytes]' offset: an identical earlier blob's in [seen], else appended. */
        private fun place(seen: HashMap<Blob, Int>, bytes: ByteArray, short: Boolean): Int {
            val blob = Blob(bytes)
            seen[blob]?.let { return it }
            val at = size
            seen[blob] = at
            blobs += bytes to at
            size += bytes.size
            if (short && at > lastShort) lastShort = at
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
