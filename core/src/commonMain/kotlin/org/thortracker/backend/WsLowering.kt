package org.thortracker.backend

import org.thortracker.backend.WsPlayerImage.DATA_CAPACITY
import org.thortracker.backend.WsPlayerImage.ENVELOPE_BYTES
import org.thortracker.backend.WsPlayerImage.ENVELOPE_COUNT
import org.thortracker.backend.WsPlayerImage.ENV_ARPEGGIO
import org.thortracker.backend.WsPlayerImage.ENV_NONE
import org.thortracker.backend.WsPlayerImage.ENV_PITCH
import org.thortracker.backend.WsPlayerImage.ENV_VOLUME
import org.thortracker.backend.WsPlayerImage.ENV_WAVE
import org.thortracker.backend.WsPlayerImage.FLAG_DEFINED
import org.thortracker.backend.WsPlayerImage.FLAG_DMA
import org.thortracker.backend.WsPlayerImage.FLAG_LFSR_RESET
import org.thortracker.backend.WsPlayerImage.FLAG_RELATIVE
import org.thortracker.backend.WsPlayerImage.FLAG_REVERSE
import org.thortracker.backend.WsPlayerImage.IMAGE_SIZE
import org.thortracker.backend.WsPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.WsPlayerImage.KIND_NOISE
import org.thortracker.backend.WsPlayerImage.KIND_SAMPLE
import org.thortracker.backend.WsPlayerImage.KIND_TONE
import org.thortracker.backend.WsPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.WsPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.WsPlayerImage.MASK_NOTE
import org.thortracker.backend.WsPlayerImage.MAX_RUN
import org.thortracker.backend.WsPlayerImage.NOTE_OFF
import org.thortracker.backend.WsPlayerImage.OFF_DATA
import org.thortracker.backend.WsPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.WsPlayerImage.OFF_ORDERS
import org.thortracker.backend.WsPlayerImage.OFF_ROWS
import org.thortracker.backend.WsPlayerImage.OFF_SAMPLES
import org.thortracker.backend.WsPlayerImage.OFF_WAVES
import org.thortracker.backend.WsPlayerImage.RUN
import org.thortracker.backend.WsPlayerImage.SAMPLE_ENTRY_SIZE
import org.thortracker.backend.WsPlayerImage.WAVE_SIZE
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.ws.WsEnvelope
import org.thortracker.system.ws.WsInstrument
import org.thortracker.system.ws.WsKind
import org.thortracker.system.ws.WsOutput
import org.thortracker.system.ws.WsRules
import org.thortracker.system.ws.WsSound
import org.thortracker.system.ws.ws

/**
 * Lowers a WonderSwan or WonderSwan Color song into the image Thor Tracker's WonderSwan player (native
 * `"ws-wonderswan"` / `"ws-wonderswan-color"`, `ws/ws_player.c`) plays and exports as VGM: the layout of
 * docs/AUDIO_ENGINE.md "WonderSwan: Image layout" (constants: [WsPlayerImage], generated from the C headers), byte for
 * byte what the reference builder `tools/audio/ws/ws_song.py` writes for the same song (`WsGoldenTest`).
 *
 * Orders are the song's arrangement (`arranged`); each order's rows = its pattern's length; the original model's lane 4
 * pattern entries stay 0. The data region holds, in this order, each in its own sharing table:
 * 1. each (order, lane)'s rows encoded as the player reads them, byte-identical encodings stored once in first-use order
 *    (orders, then lanes);
 * 2. each filled instrument's envelopes in slot order (volume, arpeggio, pitch, wave/tap; byte-identical value lists
 *    stored once, first use, whatever their kind);
 * 3. the samples' unsigned bytes in sample order (identical data stored once);
 * 4. the signed copies (each byte XOR `$80`) of the samples a Sound DMA instrument plays, in sample order (identical
 *    copies stored once; never shared with unsigned bytes). Only the Color has DMA instruments.
 * Pattern and envelope offsets are u16 (the song is refused when one would start past 65535), sample offsets u32.
 *
 * Instrument records: flags = defined | kind | relative pitch (tone, noise and CPU-fed samples, as the model says) |
 * DMA feed | LFSR reset (noise) | backwards (DMA); byte 1 the tone's wave, the noise's tap mode or the sample's index;
 * byte 2 a CPU-fed sample's root note or a DMA sample's rate; byte 3 a sample's loop flag; u16 the loop start of a
 * looping CPU-fed sample; bytes 6-7 a tone's sweep value (s8) and sweep time (any lane: only CH3 uses them); envelopes:
 * volume always, arpeggio and pitch except for DMA samples, the wave slot = a tone's wave sequence or a noise's tap
 * sequence; an empty envelope is "none" (offset 0, length 0, loop and release `$FF`). Fields of other kinds and feeds
 * are not written. Every filled slot is written (the image's instrument count is the highest), every wave and every
 * sample of the song, so the editor can audition any of them.
 */
object WsLowering {
    /** The largest offset a pattern or envelope reference holds (u16). */
    const val MAX_SHORT_OFFSET = 0xFFFF

    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's data does not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The WonderSwan image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = document.system.arranged(document)
        val sound = song.ws
        val layout = Layout(song)
        require(layout.fits) { layout.problem() }

        WsPlayerImage.MAGIC.forEachIndexed { i, ch -> out[WsPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[WsPlayerImage.H_LAYOUT] = WsPlayerImage.LAYOUT.toByte()
        out[WsPlayerImage.H_MODEL] = (if (sound.color) WsPlayerImage.MODEL_COLOR else WsPlayerImage.MODEL_MONO).toByte()
        out[WsPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        out[WsPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        u16(out, WsPlayerImage.H_ORDERS, song.orders.size)
        u16(out, WsPlayerImage.H_LOOP, song.loopOrder)
        out[WsPlayerImage.H_WAVES] = sound.waves.size.toByte()
        out[WsPlayerImage.H_SAMPLES] = sound.samples.size.toByte()
        out[WsPlayerImage.H_TIMER] = sound.timerReload.toByte()
        out[WsPlayerImage.H_SHIFT] = sound.speakerShift.toByte()
        out[WsPlayerImage.H_OUTPUT] = (if (sound.output == WsOutput.SPEAKER) WsPlayerImage.OUTPUT_SPEAKER else WsPlayerImage.OUTPUT_HEADPHONES).toByte()
        u32(out, WsPlayerImage.H_DATA_SIZE, layout.size)

        song.orders.forEachIndexed { o, p -> out[OFF_ROWS + o] = (song.patterns[p].length and 0xFF).toByte() }
        val lanes = WsRules.laneCount(sound.color)
        for (o in song.orders.indices) for (c in 0 until lanes) {
            u16(out, OFF_ORDERS + 2 * (WsPlayerImage.CHANNELS * o + c), layout.tracks[lanes * o + c])
        }
        for ((bytes, at) in layout.blobs) bytes.copyInto(out, OFF_DATA + at)

        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(ins, layout.envelopes[i]!!).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        sound.waves.forEachIndexed { w, values ->
            for (k in 0 until WAVE_SIZE) out[OFF_WAVES + WAVE_SIZE * w + k] = values[k].toByte()
        }
        sound.samples.forEachIndexed { s, sample ->
            val at = OFF_SAMPLES + SAMPLE_ENTRY_SIZE * s
            val signed = layout.signed[s]
            u32(out, at + WsPlayerImage.S_OFFSET, layout.samples[s])
            u32(out, at + WsPlayerImage.S_FRAMES, sample.frames)
            u32(out, at + WsPlayerImage.S_SIGNED_OFFSET, if (signed >= 0) signed else 0)
            out[at + WsPlayerImage.S_FLAGS] = ((if (sample.stereo) WsPlayerImage.SAMPLE_STEREO else 0) or
                (if (signed >= 0) WsPlayerImage.SAMPLE_SIGNED else 0)).toByte()
        }
    }

    /** Bytes of the data region [document] lowers to: patterns, envelopes, samples and DMA copies after sharing. */
    fun dataSize(document: Song): Int = Layout(document.system.arranged(document)).size

    /**
     * Why [document]'s data does not fit the image (the data region's capacity, or patterns and envelopes past the u16
     * offsets), or null when it fits.
     */
    fun sizeProblem(document: Song): String? = Layout(document.system.arranged(document)).let { if (it.fits) null else it.problem() }

    /** Bytes of [document]'s pattern and envelope data (they share the first 64 KB of the data region). */
    fun shortDataSize(document: Song): Int = Layout(document.system.arranged(document)).sampleStart

    /** Bytes of [document]'s signed sample copies (Sound DMA to Hyper Voice reads them), after sharing. */
    fun signedCopyBytes(document: Song): Int = Layout(document.system.arranged(document)).signedSize

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: WsSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /** The samples (indices) a Sound DMA instrument of [sound] plays: those that get a signed copy. Color only. */
    fun dmaSamples(sound: WsSound): Set<Int> =
        if (!sound.color) emptySet() else sound.instruments.filterNotNull().filter { it.isDma }.map { it.sample }.toSet()

    /**
     * The envelopes of [ins] the lowering writes, in record order (volume, arpeggio, pitch, wave): arpeggio and pitch
     * except on DMA samples, the wave slot a tone's wave sequence or a noise's tap sequence; null for an envelope written
     * as "none" (no steps).
     */
    fun lowered(ins: WsInstrument): List<WsEnvelope?> {
        val envelopes = arrayOfNulls<WsEnvelope>(ENVELOPE_COUNT)
        envelopes[ENV_VOLUME] = ins.volume
        if (!ins.isDma) {
            envelopes[ENV_ARPEGGIO] = ins.arpeggio
            envelopes[ENV_PITCH] = ins.pitch
        }
        envelopes[ENV_WAVE] = when (ins.kind) {
            WsKind.TONE -> ins.waves
            WsKind.NOISE -> ins.taps
            WsKind.SAMPLE -> null
        }
        return envelopes.map { env -> env?.takeUnless { it.isEmpty } }
    }

    /** The bytes of envelope [env] in the data region: one per step (s8 for arpeggio and pitch). */
    fun envelopeBytes(env: WsEnvelope): ByteArray = ByteArray(env.values.size) { env.values[it].toByte() }

    /**
     * The 32-byte instrument record of [ins] with its envelopes at data offsets [envelopeOffsets] (record order; any
     * value for an envelope written as none). See the class comment for the fields.
     */
    fun record(ins: WsInstrument, envelopeOffsets: IntArray): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        var flags = FLAG_DEFINED or when (ins.kind) {
            WsKind.TONE -> KIND_TONE
            WsKind.NOISE -> KIND_NOISE
            WsKind.SAMPLE -> KIND_SAMPLE
        }
        if (ins.pitchRelative && !ins.isDma) flags = flags or FLAG_RELATIVE
        if (ins.isDma) flags = flags or FLAG_DMA
        if (ins.kind == WsKind.NOISE && ins.lfsrReset) flags = flags or FLAG_LFSR_RESET
        if (ins.isDma && ins.backwards) flags = flags or FLAG_REVERSE
        r[WsPlayerImage.I_FLAGS] = flags.toByte()
        when (ins.kind) {
            WsKind.TONE -> {
                r[WsPlayerImage.I_SOURCE] = ins.wave.toByte()
                r[WsPlayerImage.I_SWEEP] = ins.sweep.toByte()
                r[WsPlayerImage.I_SWEEP_TIME] = ins.sweepTime.toByte()
            }
            WsKind.NOISE -> r[WsPlayerImage.I_SOURCE] = ins.tap.toByte()
            WsKind.SAMPLE -> {
                r[WsPlayerImage.I_SOURCE] = ins.sample.toByte()
                r[WsPlayerImage.I_ROOT] = (if (ins.isDma) ins.rate else ins.root).toByte()
                if (ins.loop) {
                    r[WsPlayerImage.I_LOOP] = 1
                    if (!ins.isDma) u16(r, WsPlayerImage.I_LOOP_START, ins.loopStart)
                }
            }
        }
        lowered(ins).forEachIndexed { k, env ->
            val at = WsPlayerImage.I_ENVELOPES + ENVELOPE_BYTES * k
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
     * Where everything of an arranged [song] goes in the data region: [tracks] = the offset of each (order, lane) at
     * index lanes · o + c, [envelopes] = per slot the offsets of its four envelopes, [samples] = each sample's unsigned
     * offset, [signed] = each sample's signed copy offset (-1 without), [blobs] = the bytes to copy and their offsets,
     * [size] = the region's used bytes.
     */
    private class Layout(song: Song) {
        val lanes = WsRules.laneCount(song.ws.color)
        val tracks = IntArray(song.orders.size * lanes)
        val envelopes = arrayOfNulls<IntArray>(WsRules.SLOTS)
        val samples: IntArray
        val signed: IntArray
        val blobs = ArrayList<Pair<ByteArray, Int>>()
        var size = 0
            private set

        /** The first byte after the patterns and envelopes (where the samples start). */
        val sampleStart: Int

        /** Bytes of the signed copies. */
        val signedSize: Int

        /** The largest pattern or envelope offset written. */
        private var lastShort = 0

        private val patternSize: Int
        private val envelopeSize: Int

        init {
            val sound = song.ws
            val patterns = HashMap<Blob, Int>()
            song.orders.forEachIndexed { o, p ->
                val pattern = song.patterns[p]
                for (c in 0 until lanes) {
                    tracks[lanes * o + c] = place(patterns, encode(pattern.tracks[c], pattern.length), short = true)
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
            samples = IntArray(sound.samples.size) { s ->
                val sample = sound.samples[s]
                place(data, ByteArray(sample.data.size) { sample.data[it].toByte() }, short = false)
            }
            val dma = dmaSamples(sound)
            val copies = HashMap<Blob, Int>()
            val signedStart = size
            signed = IntArray(sound.samples.size) { s ->
                if (s !in dma) -1
                else {
                    val sample = sound.samples[s]
                    place(copies, ByteArray(sample.data.size) { (sample.data[it] xor 0x80).toByte() }, short = false)
                }
            }
            signedSize = size - signedStart
        }

        val fits: Boolean get() = size <= DATA_CAPACITY && lastShort <= MAX_SHORT_OFFSET

        fun problem(): String = if (lastShort > MAX_SHORT_OFFSET) {
            "The song's patterns (${kb(patternSize)}) and envelopes (${kb(envelopeSize)}) pass the 64 KB their offsets " +
                "reach. Reuse patterns or shorten the song"
        } else {
            "The song's data needs ${kb(size)} (patterns ${kb(patternSize)}, envelopes ${kb(envelopeSize)}, samples " +
                "${kb(size - sampleStart - signedSize)}" + (if (signedSize > 0) ", DMA copies ${kb(signedSize)}" else "") +
                "); the WonderSwan image holds ${kb(DATA_CAPACITY)}. Shorten or remove samples, or reuse patterns"
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
