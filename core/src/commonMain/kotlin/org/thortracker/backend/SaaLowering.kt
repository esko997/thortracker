package org.thortracker.backend

import org.thortracker.backend.SaaPlayerImage.DATA_CAPACITY
import org.thortracker.backend.SaaPlayerImage.ENV_NONE
import org.thortracker.backend.SaaPlayerImage.FLAG_DEFINED
import org.thortracker.backend.SaaPlayerImage.FLAG_ENV_RETRIGGER
import org.thortracker.backend.SaaPlayerImage.FLAG_PITCH_RELATIVE
import org.thortracker.backend.SaaPlayerImage.IMAGE_SIZE
import org.thortracker.backend.SaaPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.SaaPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.SaaPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.SaaPlayerImage.MASK_NOTE
import org.thortracker.backend.SaaPlayerImage.MAX_CHANNELS
import org.thortracker.backend.SaaPlayerImage.MAX_RUN
import org.thortracker.backend.SaaPlayerImage.NOTE_OFF
import org.thortracker.backend.SaaPlayerImage.OFF_DATA
import org.thortracker.backend.SaaPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.SaaPlayerImage.OFF_ORDERS
import org.thortracker.backend.SaaPlayerImage.OFF_ROWS
import org.thortracker.backend.SaaPlayerImage.RUN
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.saa.SaaInstrument
import org.thortracker.system.saa.SaaSound
import org.thortracker.system.saa.SaaValidator

/**
 * Lowers a SAM Coupé or CMS song into the image Thor Tracker's SAA1099 player (native `"sam-saa1099"` /
 * `"cms-saa1099"`, `saa/saa_player.c`) plays and exports as VGM: the canonical layout of docs/AUDIO_ENGINE.md
 * "SAA1099: Image layout" (constants: [SaaPlayerImage], generated from the C headers), byte for byte what the
 * reference builder `tools/audio/saa/saa_song.py` (`build()`) writes for the same song (`SaaGoldenTest`).
 *
 * Header: the variant, the song's multispeed and speed, the order count and loop, the highest defined slot, the
 * channel count (6 or 12) and the data size. Orders are the song's arrangement (parked orders removed); each order's
 * rows = its pattern's length; the order table has [MAX_CHANNELS] entries per order whatever the variant. The data
 * region holds, in this order, (1) each (order, channel) track: rows 0..length − 1 encoded as the player reads them,
 * and (2) each defined slot's volume, arpeggio, pitch and mixer envelopes (6-byte header and values); every byte
 * string is appended once and an identical one already placed is reused at its first offset.
 */
object SaaLowering {
    /** The lowered image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song is not an SAA1099 song, an instrument cannot be stored, or the
     * pattern and envelope data do not fit the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The SAA1099 image needs $IMAGE_SIZE bytes" }
        val song = arrangedSong(document)
        val sound = song.sound as SaaSound
        checkStorable(sound)
        val channels = sound.variant.channels
        out.fill(0, 0, IMAGE_SIZE)
        SaaPlayerImage.MAGIC.forEachIndexed { i, ch -> out[SaaPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[SaaPlayerImage.H_LAYOUT] = SaaPlayerImage.LAYOUT.toByte()
        out[SaaPlayerImage.H_VARIANT] = sound.variant.variantByte.toByte()
        out[SaaPlayerImage.H_MULTISPEED] = sound.multispeed.toByte()
        out[SaaPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        u16(out, SaaPlayerImage.H_ORDERS, song.orders.size)
        u16(out, SaaPlayerImage.H_LOOP, song.loopOrder)
        out[SaaPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        out[SaaPlayerImage.H_CHANNELS] = channels.toByte()
        val region = Region(out, song)
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            out[OFF_ROWS + o] = (pattern.length and 0xFF).toByte()
            for (c in 0 until channels) u16(out, OFF_ORDERS + 2 * (MAX_CHANNELS * o + c), region.place(encode(pattern.tracks[c], pattern.length)))
        }
        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(ins, region).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        u16(out, SaaPlayerImage.H_DATA_SIZE, region.size)
    }

    /** Bytes of the data region [document] lowers to: tracks and envelopes after sharing identical byte strings. */
    fun dataSize(document: Song): Int {
        val song = arrangedSong(document)
        val sound = song.sound as SaaSound
        val region = Region(null, song)
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (c in 0 until sound.variant.channels) region.place(encode(pattern.tracks[c], pattern.length))
        }
        for (ins in sound.instruments) if (ins != null) record(ins, region)
        return region.size
    }

    /** The image's instrument count: the highest defined slot (0 when none). */
    fun highestSlot(sound: SaaSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /**
     * The 16-byte instrument record, placing its envelopes in [region]: flags, noise clock, pan, hardware envelope
     * control and external rate, byte 5 zero, the volume, arpeggio, pitch and mixer envelopes' data offsets (`0xFFFF`
     * for an envelope without values), bytes 14-15 zero.
     */
    private fun record(ins: SaaInstrument, region: Region): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        r[SaaPlayerImage.I_FLAGS] = flags(ins).toByte()
        r[SaaPlayerImage.I_NOISE] = ins.noise.toByte()
        r[SaaPlayerImage.I_PAN] = ins.pan.toByte()
        r[SaaPlayerImage.I_ENVELOPE] = envelopeByte(ins).toByte()
        r[SaaPlayerImage.I_RATE] = rateByte(ins).toByte()
        u16(r, SaaPlayerImage.I_VOLUME, envelopeAt(ins.volume, region))
        u16(r, SaaPlayerImage.I_ARPEGGIO, envelopeAt(ins.arpeggio, region))
        u16(r, SaaPlayerImage.I_PITCH, envelopeAt(ins.pitch, region))
        u16(r, SaaPlayerImage.I_MIXER, envelopeAt(ins.mixer, region))
        return r
    }

    /** The record's flags byte: defined, `PITCH_RELATIVE`, and `ENV_RETRIGGER` only with a hardware envelope. */
    fun flags(ins: SaaInstrument): Int =
        FLAG_DEFINED or (if (ins.pitchRelative) FLAG_PITCH_RELATIVE else 0) or
            (if (ins.envelope?.retrigger == true) FLAG_ENV_RETRIGGER else 0)

    /** Record byte 3: 0 without a hardware envelope, else its control value (0x80 | ext << 5 | 3-bit << 4 | shape << 1 | invert). */
    fun envelopeByte(ins: SaaInstrument): Int = ins.envelope?.control ?: 0

    /** Record byte 4: the external clock's rate (1..255 ticks per step), 0 for an internal clock or no envelope. */
    fun rateByte(ins: SaaInstrument): Int = ins.envelope?.takeIf { it.external }?.rate ?: 0

    private fun envelopeAt(env: NesEnvelope, region: Region): Int = envelope(env)?.let { region.place(it) } ?: ENV_NONE

    /**
     * [env] as stored: u16 length, u16 loop (`0xFFFF` none), u16 release (`0xFFFF` none), then one byte per value (two's
     * complement for arpeggio and pitch); null for an envelope without values (stored as none).
     */
    fun envelope(env: NesEnvelope): ByteArray? {
        if (env.values.isEmpty()) return null
        val b = ByteArray(SaaPlayerImage.ENV_HEADER + env.values.size)
        u16(b, 0, env.values.size)
        u16(b, 2, if (env.loop >= 0) env.loop else ENV_NONE)
        u16(b, 4, if (env.release >= 0) env.release else ENV_NONE)
        env.values.forEachIndexed { i, v -> b[SaaPlayerImage.ENV_HEADER + i] = v.toByte() }
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
        require(sound is SaaSound) { "The song is for ${document.system.name}, not the SAA1099" }
        return sound.variant.system.arranged(document)
    }

    /** What the loader would reject in the fixed tables, as the validator words it. */
    private fun checkStorable(sound: SaaSound) {
        require(sound.instruments.size == SaaSound.SLOTS) { "SAA1099 songs have exactly ${SaaSound.SLOTS} instrument slots" }
        require(sound.multispeed in 1..SaaPlayerImage.MAX_MULTISPEED) { "Multispeed ${sound.multispeed} is outside 1-${SaaPlayerImage.MAX_MULTISPEED}" }
        sound.instruments.forEachIndexed { i, ins ->
            ins?.let(SaaValidator::instrumentProblem)?.let { throw IllegalArgumentException("Instrument ${NumberText.hex(i + 1, 2)}: $it") }
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
                require(size <= DATA_CAPACITY) { SaaLowering.dataTooLarge(song) }
                bytes.copyInto(out, OFF_DATA + at)
            }
            at
        }
    }

    private fun dataTooLarge(song: Song): String =
        "The song's pattern and envelope data needs ${(dataSize(song) + 1023) / 1024} KB; the SAA1099 image holds " +
            "${DATA_CAPACITY / 1024} KB. Reuse patterns or shorten the song"

    /** Byte strings with content equality, for sharing. */
    private class Blob(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Blob && other.bytes.contentEquals(bytes)
    }
}
