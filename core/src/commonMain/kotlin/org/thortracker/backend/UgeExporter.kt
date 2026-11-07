package org.thortracker.backend

import org.thortracker.system.gb.HugeRules
import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Song

/**
 * Writes hUGETracker `.uge` format version 6, the version written by hUGETracker 1.0.11 and read by its
 * `uge2source` (layout: src/song.pas `TSongV6` / `WriteSongToStream`, src/hugedatatypes.pas; documented in
 * docs/UGE_EXPORT.md). Patterns come from [HugeLowering], so export and preview play the same data:
 * one hUGE pattern per (song pattern, channel) with key = [HugeData] track index, plus loop-variant tracks.
 *
 * @throws IllegalArgumentException when the song has [org.thortracker.system.gb.GbValidator.errors].
 */
object UgeExporter {
    const val UGE_VERSION = 6
    private const val SHORTSTRING_BYTES = 256
    private const val SUBPATTERN_ROWS = 64
    private const val ROUTINES = 16

    fun export(song: Song): ByteArray {
        val data = HugeLowering.lower(song)
        val out = LittleEndian()
        out.i32(UGE_VERSION)
        out.shortString(song.title)
        out.shortString(song.artist)
        out.shortString(song.comment)
        for (kind in listOf(InstrumentKind.PULSE, InstrumentKind.WAVE, InstrumentKind.NOISE)) {
            for (instrument in HugeRules.bank(song, kind)) writeInstrument(out, kind, instrument)
        }
        for (wave in song.waves) for (sample in wave) out.u8(sample)
        out.i32(song.ticksPerRow)
        out.bool(false) // TimerEnabled: tempo comes from the vblank tick, as in gbc-puzzler.
        out.i32(0) // TimerDivider
        out.i32(data.tracks.size)
        data.tracks.forEachIndexed { key, bytes ->
            out.i32(key)
            for (row in 0 until bytes.size / HugeLowering.ROW_BYTES) {
                val b0 = bytes[row * 3].toInt() and 0xFF
                val b1 = bytes[row * 3 + 1].toInt() and 0xFF
                out.i32(b0 and 0x7F) // note (90 = none)
                out.i32((b1 shr 4) or ((b0 and 0x80) shr 3)) // instrument
                out.i32(0) // volume column: unused in v6 song patterns
                out.i32(b1 and 0x0F) // effect code
                out.u8(bytes[row * 3 + 2].toInt() and 0xFF) // effect parameter
            }
        }
        // hUGETracker stores each order column with one extra trailing entry (its "off by one" filler);
        // uge2source and the tracker ignore the last element.
        for (column in data.orders) {
            out.i32(column.size + 1)
            for (key in column) out.i32(key)
            out.i32(0)
        }
        repeat(ROUTINES) { out.i32(0) } // empty AnsiString routines: length 0, no bytes
        return out.toByteArray()
    }

    /** One `TInstrumentV3` (1385 bytes); enums are 4 bytes, subranges/booleans 1 byte. */
    private fun writeInstrument(out: LittleEndian, kind: InstrumentKind, it: Instrument) {
        out.i32(kind.ordinal) // itSquare = 0, itWave = 1, itNoise = 2
        out.shortString(it.name)
        out.i32(it.length)
        out.bool(it.lengthEnabled)
        out.u8(it.volume) // InitialVolume
        out.i32(if (it.envelopeDirection == 1) 0 else 1) // VolSweepDirection: stUp = 0, stDown = 1
        out.u8(it.envelopePace) // VolSweepAmount
        out.i32(it.sweepTime)
        out.i32(if (it.sweepDirection == 1) 0 else 1) // SweepIncDec: stUp = 0, stDown = 1 (NR10 subtract)
        out.i32(it.sweepShift)
        out.u8(it.duty)
        out.i32(it.waveVolume) // OutputLevel (NR32 bits 5-6)
        out.i32(it.wave) // Waveform
        out.i32(it.noiseWidth) // CounterStep: swFifteen = 0, swSeven = 1
        out.bool(false) // SubpatternEnabled
        repeat(SUBPATTERN_ROWS) { // blank TCellV2: note 90, instrument 0, volume 0, effect 0, param 0
            out.i32(HugeRules.NO_NOTE); out.i32(0); out.i32(0); out.i32(0); out.u8(0)
        }
    }

    private class LittleEndian {
        private var bytes = ByteArray(80 * 1024)
        private var size = 0

        private fun write(value: Int) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = value.toByte()
        }

        fun u8(value: Int) = write(value and 0xFF)
        fun bool(value: Boolean) = write(if (value) 1 else 0)
        fun i32(value: Int) {
            write(value and 0xFF); write(value ushr 8 and 0xFF); write(value ushr 16 and 0xFF); write(value ushr 24 and 0xFF)
        }

        /** Pascal ShortString: length byte, then 255 bytes (UTF-8, zero padded). Length is validated upstream. */
        fun shortString(text: String) {
            val utf8 = text.encodeToByteArray()
            require(utf8.size <= SHORTSTRING_BYTES - 1) { "\"$text\" exceeds ${SHORTSTRING_BYTES - 1} bytes" }
            write(utf8.size)
            for (b in utf8) write(b.toInt())
            repeat(SHORTSTRING_BYTES - 1 - utf8.size) { write(0) }
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }
}
