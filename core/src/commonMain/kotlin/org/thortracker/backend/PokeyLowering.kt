package org.thortracker.backend

import org.thortracker.backend.PokeyPlayerImage.CHANNELS
import org.thortracker.backend.PokeyPlayerImage.DATA_CAPACITY
import org.thortracker.backend.PokeyPlayerImage.ENV_NONE
import org.thortracker.backend.PokeyPlayerImage.IMAGE_SIZE
import org.thortracker.backend.PokeyPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.PokeyPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.PokeyPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.PokeyPlayerImage.MASK_NOTE
import org.thortracker.backend.PokeyPlayerImage.MAX_RUN
import org.thortracker.backend.PokeyPlayerImage.NOTE_OFF
import org.thortracker.backend.PokeyPlayerImage.OFF_DATA
import org.thortracker.backend.PokeyPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.PokeyPlayerImage.OFF_ORDERS
import org.thortracker.backend.PokeyPlayerImage.OFF_ROWS
import org.thortracker.backend.PokeyPlayerImage.OFF_SAMPLES
import org.thortracker.backend.PokeyPlayerImage.OFF_SAMPLE_DATA
import org.thortracker.backend.PokeyPlayerImage.RUN
import org.thortracker.backend.PokeyPlayerImage.SAMPLE_CAPACITY
import org.thortracker.backend.PokeyPlayerImage.SAMPLE_ENTRY_SIZE
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.pokey.PokeyInstrument
import org.thortracker.system.pokey.PokeyKind
import org.thortracker.system.pokey.PokeyMachine
import org.thortracker.system.pokey.PokeySample
import org.thortracker.system.pokey.PokeySound
import org.thortracker.system.pokey.PokeySystem
import org.thortracker.system.pokey.pokey

/**
 * Lowers a POKEY song into the image Thor Tracker's POKEY player (native `"atari-pokey"`, `pokey/pokey_player.c`)
 * plays and exports as VGM: the layout of docs/AUDIO_ENGINE.md "POKEY: Image layout" (constants: [PokeyPlayerImage],
 * a hand-kept mirror of `pokey_image.h`).
 *
 * Orders are the song's arrangement ([PokeySystem.arranged]); each order's rows = its pattern's length. The data region
 * holds, from offset 0: every (order, channel)'s rows encoded as the player reads them (byte-identical encodings stored
 * once, in first-use order: orders, then channels), then the envelopes of every filled instrument slot (slot order;
 * volume, arpeggio, pitch, distortion), each byte-identical envelope stored once. An instrument writes only the fields
 * its kind uses ([record]); the rest are 0 / 0xFFFF whatever the model keeps for a later kind switch. Samples are packed
 * in index order from sample-region byte 0, each starting on a byte, two levels per byte (the earlier in the low
 * nibble; an odd length leaves the last high nibble 0).
 */
object PokeyLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song's data or samples do not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The POKEY image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = PokeySystem.arranged(document)
        val sound = song.pokey
        require(sound.instruments.size == PokeySystem.SLOTS) {
            "The song has ${sound.instruments.size} instrument slots; POKEY songs have exactly ${PokeySystem.SLOTS}"
        }
        PokeyPlayerImage.MAGIC.forEachIndexed { i, ch -> out[PokeyPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[PokeyPlayerImage.H_LAYOUT] = PokeyPlayerImage.LAYOUT.toByte()
        out[PokeyPlayerImage.H_MACHINE] = machineByte(sound.machine).toByte()
        out[PokeyPlayerImage.H_MULTISPEED] = sound.multispeed.toByte()
        out[PokeyPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        u16(out, PokeyPlayerImage.H_ORDERS, song.orders.size)
        u16(out, PokeyPlayerImage.H_LOOP, song.loopOrder)
        out[PokeyPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        out[PokeyPlayerImage.H_AUDCTL] = sound.audctlByte.toByte()

        val data = DataRegion(out)
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            out[OFF_ROWS + o] = (pattern.length and 0xFF).toByte()
            for (c in 0 until CHANNELS) {
                u16(out, OFF_ORDERS + 2 * (CHANNELS * o + c), data.put(encode(pattern.tracks[c], pattern.length), patterns = true) { tooLarge(song) })
            }
        }
        sound.instruments.forEachIndexed { i, ins ->
            if (ins == null) return@forEachIndexed
            val offsets = envelopes(ins).map { e -> if (e == null) ENV_NONE else data.put(envelope(e), patterns = false) { tooLarge(song) } }
            record(ins, offsets).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        u16(out, PokeyPlayerImage.H_DATA_SIZE, data.size)

        val sampleBytes = sampleBytes(sound.samples)
        require(sampleBytes <= SAMPLE_CAPACITY) {
            "The song's samples need ${sampleBytes * 2} levels of sample memory; the POKEY image holds ${SAMPLE_CAPACITY * 2}. " +
                "Shorten or delete samples, or import them at a lower rate"
        }
        require(sound.samples.size <= PokeyPlayerImage.MAX_SAMPLES) {
            "The song has ${sound.samples.size} samples; the POKEY image holds ${PokeyPlayerImage.MAX_SAMPLES}"
        }
        var at = 0
        sound.samples.forEachIndexed { k, sample ->
            u16(out, OFF_SAMPLES + SAMPLE_ENTRY_SIZE * k, at)
            u16(out, OFF_SAMPLES + SAMPLE_ENTRY_SIZE * k + 2, sample.levels.size)
            sample.levels.forEachIndexed { j, level ->
                val b = OFF_SAMPLE_DATA + at + j / 2
                out[b] = (out[b].toInt() or ((level and 0x0F) shl (if (j % 2 == 0) 0 else 4))).toByte()
            }
            at += (sample.levels.size + 1) / 2
        }
        u16(out, PokeyPlayerImage.H_SAMPLE_SIZE, at)
        out[PokeyPlayerImage.H_SAMPLES] = sound.samples.size.toByte()
    }

    fun machineByte(machine: PokeyMachine): Int = when (machine) {
        PokeyMachine.NTSC -> PokeyPlayerImage.MACHINE_NTSC
        PokeyMachine.PAL -> PokeyPlayerImage.MACHINE_PAL
    }

    /** Bytes of the data region (patterns and envelopes, after sharing) [document] lowers to. */
    fun dataSize(document: Song): Int {
        val song = PokeySystem.arranged(document)
        val seen = HashSet<Blob>()
        var size = 0
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (c in 0 until CHANNELS) {
                val blob = Blob(encode(pattern.tracks[c], pattern.length))
                if (seen.add(blob)) size += blob.bytes.size
            }
        }
        val envelopes = HashSet<Blob>()
        for (ins in song.pokey.instruments) {
            if (ins == null) continue
            for (e in envelopes(ins)) if (e != null) Blob(envelope(e)).let { if (envelopes.add(it)) size += it.bytes.size }
        }
        return size
    }

    /** Bytes of the sample region [samples] take. */
    fun sampleBytes(samples: List<PokeySample>): Int = samples.sumOf { (it.levels.size + 1) / 2 }

    private fun tooLarge(song: Song): String =
        "The song's patterns and envelopes need ${(dataSize(song) + 1023) / 1024} KB; the POKEY image holds ${DATA_CAPACITY / 1024} KB. " +
            "Reuse patterns or shorten the song"

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: PokeySound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The envelopes [ins] stores, in record order (volume, arpeggio, pitch, distortion); null where the kind does not use
     * one (sample sounds: volume and distortion) or it is empty.
     */
    fun envelopes(ins: PokeyInstrument): List<NesEnvelope?> {
        val sample = ins.kind == PokeyKind.SAMPLE
        return listOf(
            ins.volume.takeUnless { sample },
            ins.arpeggio,
            ins.pitch,
            ins.distortion.takeUnless { sample },
        ).map { e -> e?.takeIf { it.values.isNotEmpty() } }
    }

    /** An envelope as the image stores it: u16 length, loop, release (0xFFFF = none), then one byte per value. */
    fun envelope(e: NesEnvelope): ByteArray {
        val b = ByteArray(PokeyPlayerImage.ENV_HEADER + e.values.size)
        u16(b, 0, e.values.size)
        u16(b, 2, if (e.loop < 0) ENV_NONE else e.loop)
        u16(b, 4, if (e.release < 0) ENV_NONE else e.release)
        e.values.forEachIndexed { i, v -> b[PokeyPlayerImage.ENV_HEADER + i] = v.toByte() }
        return b
    }

    /**
     * The 32-byte instrument record with envelope data offsets [envelopes] (volume, arpeggio, pitch, distortion; 0xFFFF =
     * none). Only the fields [ins]'s kind uses are written: the filter offset for high-pass sounds, the partner AUDC for
     * 16-bit and high-pass sounds, sample, root, rate and loop for sample sounds (loop 0xFFFF otherwise).
     */
    fun record(ins: PokeyInstrument, envelopes: List<Int>): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        val kind = ins.kind
        val sample = kind == PokeyKind.SAMPLE
        var flags = PokeyPlayerImage.FLAG_DEFINED
        if (kind.joined) flags = flags or PokeyPlayerImage.FLAG_JOINED
        if (kind.filtered) flags = flags or PokeyPlayerImage.FLAG_FILTERED
        if (sample) flags = flags or PokeyPlayerImage.FLAG_SAMPLE
        if (ins.fast) flags = flags or PokeyPlayerImage.FLAG_FAST
        if (ins.pitchRelative) flags = flags or PokeyPlayerImage.FLAG_PITCH_RELATIVE
        if (kind.filtered && ins.difference) flags = flags or PokeyPlayerImage.FLAG_DIFFERENCE
        r[PokeyPlayerImage.I_FLAGS] = flags.toByte()
        if (kind.filtered) r[PokeyPlayerImage.I_FILTER_OFFSET] = ins.filterOffset.toByte()
        if (kind.joined || kind.filtered) r[PokeyPlayerImage.I_PARTNER_AUDC] = ins.partnerAudc.toByte()
        if (sample) {
            r[PokeyPlayerImage.I_SAMPLE] = ins.sample.toByte()
            r[PokeyPlayerImage.I_ROOT] = ins.root.toByte()
            u16(r, PokeyPlayerImage.I_RATE, ins.rate)
        }
        u16(r, PokeyPlayerImage.I_LOOP, if (sample && ins.loop >= 0) ins.loop else PokeyPlayerImage.LOOP_NONE)
        u16(r, PokeyPlayerImage.I_ENV_VOLUME, envelopes[0])
        u16(r, PokeyPlayerImage.I_ENV_ARPEGGIO, envelopes[1])
        u16(r, PokeyPlayerImage.I_ENV_PITCH, envelopes[2])
        u16(r, PokeyPlayerImage.I_ENV_DISTORTION, envelopes[3])
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

    /** The data region being filled: blobs appended once each (patterns and envelopes deduplicated separately). */
    private class DataRegion(private val out: ByteArray) {
        private val patterns = HashMap<Blob, Int>()
        private val envelopes = HashMap<Blob, Int>()
        var size = 0
            private set

        fun put(bytes: ByteArray, patterns: Boolean, tooLarge: () -> String): Int {
            val blob = Blob(bytes)
            val map = if (patterns) this.patterns else envelopes
            return map.getOrPut(blob) {
                val start = size
                require(start + bytes.size <= DATA_CAPACITY) { tooLarge() }
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
