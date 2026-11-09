package org.thortracker.backend

import org.thortracker.backend.SccPlayerImage.CHANNELS
import org.thortracker.backend.SccPlayerImage.DATA_CAPACITY
import org.thortracker.backend.SccPlayerImage.ENV_NONE
import org.thortracker.backend.SccPlayerImage.FLAG_DEFINED
import org.thortracker.backend.SccPlayerImage.FLAG_PITCH_RELATIVE
import org.thortracker.backend.SccPlayerImage.FLAG_RESTART
import org.thortracker.backend.SccPlayerImage.IMAGE_SIZE
import org.thortracker.backend.SccPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.SccPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.SccPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.SccPlayerImage.MASK_NOTE
import org.thortracker.backend.SccPlayerImage.MAX_RUN
import org.thortracker.backend.SccPlayerImage.MAX_WAVES
import org.thortracker.backend.SccPlayerImage.NOTE_OFF
import org.thortracker.backend.SccPlayerImage.OFF_DATA
import org.thortracker.backend.SccPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.SccPlayerImage.OFF_ORDERS
import org.thortracker.backend.SccPlayerImage.OFF_ROWS
import org.thortracker.backend.SccPlayerImage.OFF_WAVES
import org.thortracker.backend.SccPlayerImage.RUN
import org.thortracker.backend.SccPlayerImage.WAVE_BYTES
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.scc.SccInstrument
import org.thortracker.system.scc.SccSound
import org.thortracker.system.scc.SccValidator
import org.thortracker.system.scc.SccVariant

/**
 * Lowers an SCC or SCC+ song into the image Thor Tracker's SCC player (native `"msx-scc"` / `"msx-scc-plus"`,
 * `scc/scc_player.c`) plays and exports as VGM: the canonical layout of docs/AUDIO_ENGINE.md "SCC and SCC+: Image
 * layout" (constants: [SccPlayerImage], generated from the C headers), byte for byte what the reference builder
 * `tools/audio/scc/scc_song.py` (`build()`) writes for the same song (`SccGoldenTest`).
 *
 * Header: the song's speed and multispeed, the variant, the highest defined slot, wave count 64 (every wave of the
 * song) and the data size. Orders are the song's arrangement (parked orders removed); each order's rows = its
 * pattern's length. The data region holds, in this order, (1) each (order, channel) track: rows 0..length − 1 encoded
 * as the player reads them, and (2) each defined slot's volume, arpeggio, pitch and wave envelopes (6-byte header and
 * values); every byte string is appended once and an identical one already placed is reused at its first offset.
 */
object SccLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song is not an SCC song, an instrument or wave cannot be stored, or the
     * pattern and envelope data do not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The SCC image needs $IMAGE_SIZE bytes" }
        val song = arrangedSong(document)
        val sound = song.sound as SccSound
        checkStorable(sound)
        out.fill(0, 0, IMAGE_SIZE)
        SccPlayerImage.MAGIC.forEachIndexed { i, ch -> out[SccPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[SccPlayerImage.H_LAYOUT] = SccPlayerImage.LAYOUT.toByte()
        out[SccPlayerImage.H_VARIANT] = variantByte(sound.variant).toByte()
        out[SccPlayerImage.H_MULTISPEED] = sound.multispeed.toByte()
        out[SccPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        u16(out, SccPlayerImage.H_ORDERS, song.orders.size)
        u16(out, SccPlayerImage.H_LOOP, song.loopOrder)
        out[SccPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        out[SccPlayerImage.H_WAVES] = MAX_WAVES.toByte()
        sound.waves.forEachIndexed { k, wave ->
            wave.samples.forEachIndexed { i, v -> out[OFF_WAVES + WAVE_BYTES * k + i] = v.toByte() }
        }
        val region = Region(out, song)
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            out[OFF_ROWS + o] = (pattern.length and 0xFF).toByte()
            for (c in 0 until CHANNELS) u16(out, OFF_ORDERS + 2 * (CHANNELS * o + c), region.place(encode(pattern.tracks[c], pattern.length)))
        }
        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(ins, region).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        u16(out, SccPlayerImage.H_DATA_SIZE, region.size)
    }

    /** Bytes of the data region [document] lowers to: tracks and envelopes after sharing identical byte strings. */
    fun dataSize(document: Song): Int {
        val song = arrangedSong(document)
        val sound = song.sound as SccSound
        val region = Region(null, song)
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (c in 0 until CHANNELS) region.place(encode(pattern.tracks[c], pattern.length))
        }
        for (ins in sound.instruments) if (ins != null) record(ins, region)
        return region.size
    }

    /** The image's variant byte. */
    fun variantByte(variant: SccVariant): Int = when (variant) {
        SccVariant.SCC -> SccPlayerImage.VARIANT_SCC
        SccVariant.PLUS -> SccPlayerImage.VARIANT_PLUS
    }

    /** The image's instrument count: the highest defined slot (0 when none). */
    fun highestSlot(sound: SccSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The 16-byte instrument record, placing its envelopes in [region]: flags (defined, `RESTART`, `PITCH_RELATIVE`),
     * byte 1 zero, the volume, arpeggio, pitch and wave envelopes' data offsets (`0xFFFF` for an envelope without
     * values), the rest zero.
     */
    private fun record(ins: SccInstrument, region: Region): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        r[SccPlayerImage.I_FLAGS] = flags(ins).toByte()
        u16(r, SccPlayerImage.I_VOLUME, envelopeAt(ins.volume, region))
        u16(r, SccPlayerImage.I_ARPEGGIO, envelopeAt(ins.arpeggio, region))
        u16(r, SccPlayerImage.I_PITCH, envelopeAt(ins.pitch, region))
        u16(r, SccPlayerImage.I_WAVE, envelopeAt(ins.wave, region))
        return r
    }

    /** The record's flags byte. */
    fun flags(ins: SccInstrument): Int =
        FLAG_DEFINED or (if (ins.restart) FLAG_RESTART else 0) or (if (ins.pitchRelative) FLAG_PITCH_RELATIVE else 0)

    private fun envelopeAt(env: NesEnvelope, region: Region): Int = envelope(env)?.let { region.place(it) } ?: ENV_NONE

    /**
     * [env] as stored: u16 length, u16 loop (`0xFFFF` none), u16 release (`0xFFFF` none), then one byte per value (two's
     * complement for arpeggio and pitch); null for an envelope without values (stored as none).
     */
    fun envelope(env: NesEnvelope): ByteArray? {
        if (env.values.isEmpty()) return null
        val b = ByteArray(SccPlayerImage.ENV_HEADER + env.values.size)
        u16(b, 0, env.values.size)
        u16(b, 2, if (env.loop >= 0) env.loop else ENV_NONE)
        u16(b, 4, if (env.release >= 0) env.release else ENV_NONE)
        env.values.forEachIndexed { i, v -> b[SccPlayerImage.ENV_HEADER + i] = v.toByte() }
        return b
    }

    /**
     * [rows] rows of [track] as the player reads them: empty rows in runs (`0x80 | k` = k + 1 rows, at most 128), else
     * a mask (bit 0 note, bit 1 instrument, bits 2-4 effect count), the note (`0xFE` = OFF), the slot and each non-blank
     * effect column's code and parameter in column order.
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
            var effects = 0
            for (col in 0..cell.more.size) if (cell.effectCode(col) != 0 || cell.effectParam(col) != 0) effects++
            val hasNote = cell.note != Cell.EMPTY
            val hasInstrument = cell.instrument != 0
            if (!hasNote && !hasInstrument && effects == 0) { empty++; continue }
            flush()
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

    private fun arrangedSong(document: Song): Song {
        val sound = document.sound
        require(sound is SccSound) { "The song is for ${document.system.name}, not the SCC" }
        return sound.variant.system.arranged(document)
    }

    /** What the loader would reject in the fixed tables, as the validator words it. */
    private fun checkStorable(sound: SccSound) {
        require(sound.instruments.size == SccSound.SLOTS) { "SCC songs have exactly ${SccSound.SLOTS} instrument slots" }
        require(sound.waves.size == SccSound.WAVES) { "SCC songs have exactly ${SccSound.WAVES} waves" }
        require(sound.multispeed in 1..SccPlayerImage.MAX_MULTISPEED) { "Multispeed ${sound.multispeed} is outside 1-${SccPlayerImage.MAX_MULTISPEED}" }
        sound.waves.forEachIndexed { k, w -> w.problem()?.let { throw IllegalArgumentException("Wave ${NumberText.hex(k + 1, 2)} $it") } }
        sound.instruments.forEachIndexed { i, ins ->
            ins?.let(SccValidator::instrumentProblem)?.let { throw IllegalArgumentException("Instrument ${NumberText.hex(i + 1, 2)}: $it") }
        }
    }

    private fun u16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = (value shr 8 and 0xFF).toByte()
    }

    /**
     * The data region being filled: each new byte string appended at the end, an identical one already placed reused
     * (its first offset). With [out] null only the size is counted (no capacity check).
     */
    private class Region(private val out: ByteArray?, private val song: Song) {
        private val placed = HashMap<Blob, Int>()
        var size = 0
            private set

        fun place(bytes: ByteArray): Int = placed.getOrPut(Blob(bytes)) {
            val at = size
            size += bytes.size
            if (out != null) {
                require(size <= DATA_CAPACITY) { SccLowering.dataTooLarge(song) }
                bytes.copyInto(out, OFF_DATA + at)
            }
            at
        }
    }

    private fun dataTooLarge(song: Song): String =
        "The song's pattern and envelope data needs ${(dataSize(song) + 1023) / 1024} KB; the SCC image holds " +
            "${DATA_CAPACITY / 1024} KB. Reuse patterns or shorten the song"

    /** Byte strings with content equality, for sharing. */
    private class Blob(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Blob && other.bytes.contentEquals(bytes)
    }
}
