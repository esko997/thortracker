package org.thortracker.backend

import org.thortracker.backend.PcePlayerImage.CHANNELS
import org.thortracker.backend.PcePlayerImage.DATA_CAPACITY
import org.thortracker.backend.PcePlayerImage.ENVELOPE_BYTES
import org.thortracker.backend.PcePlayerImage.ENV_ARPEGGIO
import org.thortracker.backend.PcePlayerImage.ENV_NONE
import org.thortracker.backend.PcePlayerImage.ENV_PITCH
import org.thortracker.backend.PcePlayerImage.ENV_VOLUME
import org.thortracker.backend.PcePlayerImage.ENV_WAVE
import org.thortracker.backend.PcePlayerImage.FLAG_DEFINED
import org.thortracker.backend.PcePlayerImage.FLAG_RELATIVE
import org.thortracker.backend.PcePlayerImage.IMAGE_SIZE
import org.thortracker.backend.PcePlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.PcePlayerImage.KIND_NOISE
import org.thortracker.backend.PcePlayerImage.KIND_SAMPLE
import org.thortracker.backend.PcePlayerImage.KIND_TONE
import org.thortracker.backend.PcePlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.PcePlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.PcePlayerImage.MASK_NOTE
import org.thortracker.backend.PcePlayerImage.MAX_RUN
import org.thortracker.backend.PcePlayerImage.NOTE_OFF
import org.thortracker.backend.PcePlayerImage.OFF_DATA
import org.thortracker.backend.PcePlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.PcePlayerImage.OFF_ORDERS
import org.thortracker.backend.PcePlayerImage.OFF_ROWS
import org.thortracker.backend.PcePlayerImage.OFF_SAMPLES
import org.thortracker.backend.PcePlayerImage.OFF_WAVES
import org.thortracker.backend.PcePlayerImage.RUN
import org.thortracker.backend.PcePlayerImage.SAMPLE_ENTRY_SIZE
import org.thortracker.backend.PcePlayerImage.WAVE_SIZE
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.pce.PceEnvelope
import org.thortracker.system.pce.PceInstrument
import org.thortracker.system.pce.PceKind
import org.thortracker.system.pce.PceSound
import org.thortracker.system.pce.PceSystem
import org.thortracker.system.pce.pce

/**
 * Lowers a PC Engine song into the image Thor Tracker's HuC6280 player (native `"pce-huc6280"`, `pce/pce_player.c`)
 * plays and exports as VGM: the layout of docs/AUDIO_ENGINE.md "PC Engine: Image layout" (constants: [PcePlayerImage],
 * generated from the C headers), byte for byte what the reference builder `tools/audio/pce/pce_song.py` (`build()`)
 * writes for the same song (`PceGoldenTest`).
 *
 * Orders are the song's arrangement ([PceSystem.arranged]); each order's rows = its pattern's length. The data region
 * holds, in this order: each (order, channel)'s rows encoded as the player reads them, byte-identical encodings stored
 * once in first-use order (orders, then channels); then each filled instrument's envelopes in slot order (volume,
 * arpeggio, pitch, wave; byte-identical value lists stored once, first use); then the samples in sample order
 * (identical data stored once). Pattern and envelope offsets are u16, sample offsets u32, so patterns and envelopes
 * come first. Every filled slot is written (the image's instrument count is the highest), every wave and every sample
 * of the song, so the editor can audition any of them. The header carries the chip's shared state at the song start:
 * master balance, LFO frequency and control, and the DDA timer.
 */
object PceLowering {
    /** The largest offset a pattern or envelope reference holds (u16). */
    const val MAX_SHORT_OFFSET = 0xFFFF

    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's data does not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The PC Engine image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = PceSystem.arranged(document)
        val sound = song.pce
        val layout = Layout(song)
        require(layout.fits) { layout.problem() }

        PcePlayerImage.MAGIC.forEachIndexed { i, ch -> out[PcePlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[PcePlayerImage.H_LAYOUT] = PcePlayerImage.LAYOUT.toByte()
        out[PcePlayerImage.H_MULTISPEED] = sound.multispeed.toByte()
        out[PcePlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        out[PcePlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        u16(out, PcePlayerImage.H_ORDERS, song.orders.size)
        u16(out, PcePlayerImage.H_LOOP, song.loopOrder)
        out[PcePlayerImage.H_WAVES] = sound.waves.size.toByte()
        out[PcePlayerImage.H_SAMPLES] = sound.samples.size.toByte()
        out[PcePlayerImage.H_MASTER] = sound.master.toByte()
        out[PcePlayerImage.H_LFO_FREQ] = sound.lfoFrequency.toByte()
        out[PcePlayerImage.H_LFO_CTRL] = sound.lfoControl.toByte()
        out[PcePlayerImage.H_TIMER] = sound.timerReload.toByte()
        u32(out, PcePlayerImage.H_DATA_SIZE, layout.size)

        song.orders.forEachIndexed { o, p -> out[OFF_ROWS + o] = (song.patterns[p].length and 0xFF).toByte() }
        layout.tracks.forEachIndexed { i, at -> u16(out, OFF_ORDERS + 2 * i, at) }
        for ((bytes, at) in layout.blobs) bytes.copyInto(out, OFF_DATA + at)

        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(ins, layout.envelopes[i]!!).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        sound.waves.forEachIndexed { w, values ->
            for (k in 0 until WAVE_SIZE) out[OFF_WAVES + WAVE_SIZE * w + k] = values[k].toByte()
        }
        sound.samples.forEachIndexed { s, sample ->
            u32(out, OFF_SAMPLES + SAMPLE_ENTRY_SIZE * s, layout.samples[s])
            u32(out, OFF_SAMPLES + SAMPLE_ENTRY_SIZE * s + 4, sample.data.size)
        }
    }

    /** Bytes of the data region [document] lowers to: patterns, envelopes and samples after sharing identical ones. */
    fun dataSize(document: Song): Int = Layout(PceSystem.arranged(document)).size

    /**
     * Why [document]'s data does not fit the image (the data region's capacity, or patterns and envelopes past the
     * u16 offsets), or null when it fits.
     */
    fun sizeProblem(document: Song): String? = Layout(PceSystem.arranged(document)).let { if (it.fits) null else it.problem() }

    /** Bytes of [document]'s pattern and envelope data (they share the first 64 KB of the data region). */
    fun shortDataSize(document: Song): Int = Layout(PceSystem.arranged(document)).sampleStart

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: PceSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The envelopes of [ins] the lowering writes, in record order (volume, arpeggio, pitch, wave): the wave envelope
     * only on a tone sound; null for an envelope written as "none" (no steps).
     */
    fun lowered(ins: PceInstrument): List<PceEnvelope?> {
        val envelopes = arrayOfNulls<PceEnvelope>(PcePlayerImage.ENVELOPE_COUNT)
        envelopes[ENV_VOLUME] = ins.volume
        envelopes[ENV_ARPEGGIO] = ins.arpeggio
        envelopes[ENV_PITCH] = ins.pitch
        envelopes[ENV_WAVE] = if (ins.kind == PceKind.TONE) ins.waves else null
        return envelopes.map { env -> env?.takeUnless { it.isEmpty } }
    }

    /** The bytes of envelope [env] in the data region: one per step (s8 for arpeggio and pitch). */
    fun envelopeBytes(env: PceEnvelope): ByteArray = ByteArray(env.values.size) { env.values[it].toByte() }

    /**
     * The 32-byte instrument record of [ins] with its envelopes at data offsets [envelopeOffsets] (record order; any
     * value for an envelope without steps): flags (defined, kind, relative pitch), the tone's wave, the sample's index,
     * root, loop flag and loop start (zero for other kinds and without loop), then per envelope offset, length, loop
     * and release (`$FF` = none).
     */
    fun record(ins: PceInstrument, envelopeOffsets: IntArray): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        val kind = when (ins.kind) {
            PceKind.TONE -> KIND_TONE
            PceKind.NOISE -> KIND_NOISE
            PceKind.SAMPLE -> KIND_SAMPLE
        }
        r[PcePlayerImage.I_FLAGS] = (FLAG_DEFINED or kind or (if (ins.pitchRelative) FLAG_RELATIVE else 0)).toByte()
        if (ins.kind == PceKind.TONE) r[PcePlayerImage.I_WAVE] = ins.wave.toByte()
        if (ins.kind == PceKind.SAMPLE) {
            r[PcePlayerImage.I_SAMPLE] = ins.sample.toByte()
            r[PcePlayerImage.I_ROOT] = ins.root.toByte()
            if (ins.loop) {
                r[PcePlayerImage.I_LOOP] = 1
                u16(r, PcePlayerImage.I_LOOP_START, ins.loopStart)
            }
        }
        lowered(ins).forEachIndexed { k, env ->
            val at = PcePlayerImage.I_ENVELOPES + ENVELOPE_BYTES * k
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
     * at index 6 o + c, [envelopes] = per slot the offsets of its four envelopes, [samples] = each sample's offset,
     * [blobs] = the bytes to copy and their offsets, [size] = the region's used bytes.
     */
    private class Layout(song: Song) {
        val tracks = IntArray(song.orders.size * CHANNELS)
        val envelopes = arrayOfNulls<IntArray>(PceSystem.SLOTS)
        val samples: IntArray
        val blobs = ArrayList<Pair<ByteArray, Int>>()
        var size = 0
            private set

        /** The first byte after the patterns and envelopes (where the samples start). */
        val sampleStart: Int

        /** The largest pattern or envelope offset written. */
        private var lastShort = 0

        private val patternSize: Int
        private val envelopeSize: Int

        init {
            val sound = song.pce
            val patterns = HashMap<Blob, Int>()
            song.orders.forEachIndexed { o, p ->
                val pattern = song.patterns[p]
                for (c in 0 until CHANNELS) {
                    tracks[CHANNELS * o + c] = place(patterns, encode(pattern.tracks[c], pattern.length), short = true)
                }
            }
            patternSize = size
            val shared = HashMap<Blob, Int>()
            sound.instruments.forEachIndexed { i, ins ->
                if (ins == null) return@forEachIndexed
                val at = IntArray(PcePlayerImage.ENVELOPE_COUNT)
                lowered(ins).forEachIndexed { k, env -> if (env != null) at[k] = place(shared, envelopeBytes(env), short = true) }
                envelopes[i] = at
            }
            envelopeSize = size - patternSize
            sampleStart = size
            val data = HashMap<Blob, Int>()
            samples = IntArray(sound.samples.size) { s ->
                val sample = sound.samples[s]
                place(data, ByteArray(sample.data.size) { sample.data[it].toByte() }, short = false)
            }
        }

        val fits: Boolean get() = size <= DATA_CAPACITY && lastShort <= MAX_SHORT_OFFSET

        fun problem(): String = if (lastShort > MAX_SHORT_OFFSET) {
            "The song's patterns (${kb(patternSize)}) and envelopes (${kb(envelopeSize)}) pass the 64 KB their offsets " +
                "reach. Reuse patterns or shorten the song"
        } else {
            "The song's data needs ${kb(size)} (patterns ${kb(patternSize)}, envelopes ${kb(envelopeSize)}, samples " +
                "${kb(size - sampleStart)}); the PC Engine image holds ${kb(DATA_CAPACITY)}. Shorten or remove samples, or " +
                "reuse patterns"
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
