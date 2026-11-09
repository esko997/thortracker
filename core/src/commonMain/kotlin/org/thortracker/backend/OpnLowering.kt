package org.thortracker.backend

import org.thortracker.backend.OpnPlayerImage.DATA_CAPACITY
import org.thortracker.backend.OpnPlayerImage.ENV_HEADER
import org.thortracker.backend.OpnPlayerImage.FLAG_DEFINED
import org.thortracker.backend.OpnPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.OpnPlayerImage.MASK_EFFECT_SHIFT
import org.thortracker.backend.OpnPlayerImage.MASK_INSTRUMENT
import org.thortracker.backend.OpnPlayerImage.MASK_NOTE
import org.thortracker.backend.OpnPlayerImage.MAX_CHANNELS
import org.thortracker.backend.OpnPlayerImage.MAX_RUN
import org.thortracker.backend.OpnPlayerImage.NOTE_OFF
import org.thortracker.backend.OpnPlayerImage.NO_ENVELOPE
import org.thortracker.backend.OpnPlayerImage.OFF_ADPCM
import org.thortracker.backend.OpnPlayerImage.OFF_DATA
import org.thortracker.backend.OpnPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.OpnPlayerImage.OFF_ORDERS
import org.thortracker.backend.OpnPlayerImage.OFF_ROWS
import org.thortracker.backend.OpnPlayerImage.OFF_SAMPLES
import org.thortracker.backend.OpnPlayerImage.OP_BYTES
import org.thortracker.backend.OpnPlayerImage.RUN
import org.thortracker.backend.OpnPlayerImage.SAMPLE_SIZE
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.opn.OpnAdpcmSound
import org.thortracker.system.opn.OpnFmSound
import org.thortracker.system.opn.OpnInstrument
import org.thortracker.system.opn.OpnRhythmSound
import org.thortracker.system.opn.OpnSound
import org.thortracker.system.opn.OpnSsgSound
import org.thortracker.system.opn.OpnValidator
import org.thortracker.system.opn.opn
import org.thortracker.system.opn.system
import org.thortracker.system.ym2612.YmSweep

/**
 * Lowers a YM2203 or YM2608 song into the image Thor Tracker's OPN player (native `"ym2203-opn"` / `"ym2608-opna"`,
 * `opn/opn_player.c`) plays and its VGM export renders: exactly the layout of `opn/opn_image.h` (mirrored in
 * [OpnPlayerImage]).
 *
 * Orders are the song's arrangement ([org.thortracker.system.SoundSystem.arranged]); each order's rows = its pattern's
 * length; each (order, channel) is that track's rows encoded as the player reads them, byte-identical encodings stored
 * once in first-use order (orders, then channels). The SSG envelopes follow the patterns in the data region: slots
 * ascending, each slot's volume, arpeggio, pitch, mixer and noise envelope, byte-identical envelopes stored once.
 * Instruments are written for every filled slot (the image's instrument count is the highest), so the editor can
 * audition any of them. On the YM2608 every sample of the song is placed in the ADPCM-B memory in song order from
 * address 0, each at a 32-byte boundary (its data padded with zero bytes to a multiple of 32).
 *
 * The lowering never needs the rhythm ROM (the image only names the drums); the playback engine refuses songs whose
 * played orders hold rhythm notes while it is missing ([org.thortracker.system.fm.Ym2608RhythmRom]).
 */
object OpnLowering {
    /** The lowered image of [document] (as played: parked orders left out), [org.thortracker.system.opn.OpnChip.imageSize] bytes. */
    fun lower(document: Song): ByteArray = ByteArray(document.opn.chip.imageSize).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] (at least the chip's image size; that many bytes are overwritten entirely).
     * @throws IllegalArgumentException with an actionable message when a sound or sample cannot be stored for the chip
     * (the YM2203's missing YM2608 features included), the samples exceed the ADPCM-B memory or their table, or the
     * pattern and envelope data exceed the image.
     */
    fun lower(document: Song, out: ByteArray) {
        val sound = document.opn
        val chip = sound.chip
        val size = chip.imageSize
        require(out.size >= size) { "The ${chip.label} image needs $size bytes" }
        representable(sound)?.let { throw IllegalArgumentException(it) }
        out.fill(0, 0, size)
        val song = chip.system().arranged(document)
        OpnPlayerImage.MAGIC.forEachIndexed { i, ch -> out[OpnPlayerImage.H_MAGIC + i] = ch.code.toByte() }
        out[OpnPlayerImage.H_LAYOUT] = OpnPlayerImage.LAYOUT.toByte()
        out[OpnPlayerImage.H_CHIP] = chip.code.toByte()
        out[OpnPlayerImage.H_SPEED] = song.ticksPerRow.toByte()
        out[OpnPlayerImage.H_LFO] = (if (chip.opna) sound.lfo else 0).toByte()
        u16(out, OpnPlayerImage.H_ORDERS, song.orders.size)
        u16(out, OpnPlayerImage.H_LOOP, song.loopOrder)
        out[OpnPlayerImage.H_INSTRUMENTS] = highestSlot(sound).toByte()
        out[OpnPlayerImage.H_FM3_MODE] = sound.fm3Mode.code.toByte()
        u16(out, OpnPlayerImage.H_TIMER_A, sound.timerA)
        out[OpnPlayerImage.H_TIMER_B] = sound.timerB.toByte()
        out[OpnPlayerImage.H_RHYTHM_LEVEL] = (if (chip.opna) sound.rhythmLevel else 0).toByte()

        val data = Data(out, song)
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            out[OFF_ROWS + o] = (pattern.length and 0xFF).toByte()
            for (c in 0 until chip.channelCount) {
                u16(out, OFF_ORDERS + 2 * (MAX_CHANNELS * o + c), data.patterns.put(encode(pattern.tracks[c], pattern.length)))
            }
        }
        sound.instruments.forEachIndexed { i, ins ->
            if (ins != null) record(ins, data::envelope).copyInto(out, OFF_INSTRUMENTS + INSTRUMENT_SIZE * i)
        }
        u16(out, OpnPlayerImage.H_DATA_SIZE, data.size)

        if (chip.opna) {
            var address = 0
            sound.samples.forEachIndexed { k, sample ->
                val entry = OFF_SAMPLES + SAMPLE_SIZE * k
                u32(out, entry + OpnPlayerImage.S_START, address)
                u32(out, entry + OpnPlayerImage.S_END, address + sample.stored)
                u32(out, entry + OpnPlayerImage.S_RATE, sample.rate)
                sample.data.copyInto(out, OFF_ADPCM + address)
                address += sample.stored
            }
            out[OpnPlayerImage.H_SAMPLES] = sound.samples.size.toByte()
            u32(out, OpnPlayerImage.H_ADPCM_SIZE, address)
        }
    }

    /**
     * Why [sound] cannot be written as an image of its chip (the first of [OpnValidator.soundProblems]: sounds, samples,
     * the YM2203's missing features), or null.
     */
    fun representable(sound: OpnSound): String? = OpnValidator.soundProblems(sound).firstOrNull()

    /** Bytes of pattern and envelope data [document] lowers to (after sharing identical tracks and envelopes). */
    fun dataSize(document: Song): Int {
        val chip = document.opn.chip
        val song = chip.system().arranged(document)
        val patterns = HashSet<Blob>()
        val envelopes = HashSet<Blob>()
        var size = 0
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (c in 0 until chip.channelCount) {
                val blob = Blob(encode(pattern.tracks[c], pattern.length))
                if (patterns.add(blob)) size += blob.bytes.size
            }
        }
        for (ins in song.opn.instruments) {
            if (ins !is OpnSsgSound) continue
            for (env in envelopesOf(ins)) {
                if (env.values.isEmpty()) continue
                val blob = Blob(envelope(env))
                if (envelopes.add(blob)) size += blob.bytes.size
            }
        }
        return size
    }

    /** The image's instrument count: the highest filled slot (0 when none). */
    fun highestSlot(sound: OpnSound): Int = sound.instruments.indexOfLast { it != null } + 1

    /** An SSG sound's envelopes in the order their offsets are stored and placed. */
    private fun envelopesOf(ins: OpnSsgSound) = listOf(ins.volume, ins.arpeggio, ins.pitch, ins.mixer, ins.noise)

    /**
     * The 48-byte instrument record; an SSG record's envelope fields are the data-region offsets [place] returns for
     * each non-empty envelope's bytes ([NO_ENVELOPE] for an empty one). Every byte the kind does not name stays 0.
     */
    fun record(ins: OpnInstrument, place: (NesEnvelope) -> Int): ByteArray {
        val r = ByteArray(INSTRUMENT_SIZE)
        when (ins) {
            is OpnFmSound -> {
                r[OpnPlayerImage.I_FLAGS] = (FLAG_DEFINED or OpnPlayerImage.KIND_FM).toByte()
                r[OpnPlayerImage.I_ALG] = ins.algorithm.toByte()
                r[OpnPlayerImage.I_FEEDBACK] = ins.feedback.toByte()
                r[OpnPlayerImage.I_AMS_FMS] = ((ins.ams shl 4) or ins.fms).toByte()
                ins.operators.forEachIndexed { k, op ->
                    val at = OpnPlayerImage.I_OPS + OP_BYTES * k
                    intArrayOf(op.reg30, op.reg40, op.reg50, op.reg60, op.reg70, op.reg80, op.reg90)
                        .forEachIndexed { i, v -> r[at + i] = v.toByte() }
                }
                ins.ch3.forEachIndexed { k, offset -> u16(r, OpnPlayerImage.I_CH3 + 2 * k, offset and 0xFFFF) }
                sweep(r, ins.sweep)
            }
            is OpnSsgSound -> {
                r[OpnPlayerImage.I_FLAGS] = (FLAG_DEFINED or OpnPlayerImage.KIND_SSG).toByte()
                r[OpnPlayerImage.I_SSG_FLAGS] = (if (ins.pitchRelative) OpnPlayerImage.SSG_PITCH_RELATIVE else 0).toByte()
                r[OpnPlayerImage.I_SSG_SHAPE] = ins.envelope.shape.register.toByte()
                r[OpnPlayerImage.I_SSG_AUTO] = (if (ins.envelope.autoPitch) 1 else 0).toByte()
                r[OpnPlayerImage.I_SSG_OCTAVE] = ins.envelope.octave.toByte()
                u16(r, OpnPlayerImage.I_SSG_PERIOD, ins.envelope.period)
                fun env(at: Int, e: NesEnvelope) = u16(r, at, if (e.values.isEmpty()) NO_ENVELOPE else place(e))
                env(OpnPlayerImage.I_ENV_VOLUME, ins.volume)
                env(OpnPlayerImage.I_ENV_ARPEGGIO, ins.arpeggio)
                env(OpnPlayerImage.I_ENV_PITCH, ins.pitch)
                env(OpnPlayerImage.I_ENV_MIXER, ins.mixer)
                env(OpnPlayerImage.I_ENV_NOISE, ins.noise)
                sweep(r, ins.sweep)
            }
            is OpnRhythmSound -> {
                r[OpnPlayerImage.I_FLAGS] = (FLAG_DEFINED or OpnPlayerImage.KIND_RHYTHM).toByte()
                r[OpnPlayerImage.I_RHYTHM_LEVEL] = ins.level.toByte()
                r[OpnPlayerImage.I_PAN] = pan(ins.left, ins.right).toByte()
            }
            is OpnAdpcmSound -> {
                r[OpnPlayerImage.I_FLAGS] = (FLAG_DEFINED or OpnPlayerImage.KIND_ADPCM).toByte()
                r[OpnPlayerImage.I_PAN] = pan(ins.left, ins.right).toByte()
                r[OpnPlayerImage.I_ADPCM_LEVEL] = ins.level.toByte()
                r[OpnPlayerImage.I_ADPCM_SAMPLE] = ins.sample.toByte()
                r[OpnPlayerImage.I_ADPCM_ROOT] = ins.root.toByte()
                r[OpnPlayerImage.I_ADPCM_FLAGS] = (if (ins.loop) OpnPlayerImage.ADPCM_LOOP else 0).toByte()
                sweep(r, ins.sweep)
            }
        }
        return r
    }

    /** Pan bits: 7 left, 6 right. */
    fun pan(left: Boolean, right: Boolean): Int = (if (left) 0x80 else 0) or (if (right) 0x40 else 0)

    /** The sweep bytes; all 0 without a sweep (speed 0), as the loader requires. */
    private fun sweep(r: ByteArray, sweep: YmSweep) {
        if (!sweep.active) return
        r[OpnPlayerImage.I_SWEEP_START] = sweep.start.toByte()
        r[OpnPlayerImage.I_SWEEP_END] = sweep.end.toByte()
        r[OpnPlayerImage.I_SWEEP_SPEED] = sweep.speed.toByte()
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

    private fun u32(out: ByteArray, at: Int, value: Int) {
        u16(out, at, value and 0xFFFF)
        u16(out, at + 2, value ushr 16)
    }

    private fun dataTooLarge(song: Song): String =
        "The song's pattern and envelope data need ${(dataSize(song) + 1023) / 1024} KiB; the ${song.opn.chip.label} image " +
            "holds ${DATA_CAPACITY / 1024} KiB. Reuse patterns or shorten the song"

    /** The data region being filled: patterns first, then envelopes, each kind sharing identical bytes. */
    private class Data(private val out: ByteArray, private val song: Song) {
        var size = 0
            private set
        val patterns = Pool()
        private val envelopes = Pool()

        fun envelope(env: NesEnvelope): Int = envelopes.put(OpnLowering.envelope(env))

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
