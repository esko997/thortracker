package org.thortracker.backend

import org.thortracker.system.gb.gb
import org.thortracker.model.Cell
import org.thortracker.system.gb.HugeRules
import org.thortracker.system.gb.GbInstrument
import org.thortracker.model.Song
import org.thortracker.system.gb.GbValidator

/**
 * A validated song in hUGEDriver 6.1.3 data layout — the single lowering shared by preview playback
 * ([org.thortracker.audio]) and `.uge` export ([UgeExporter]). All arrays are freshly allocated and must be
 * treated as read-only.
 *
 * Byte layouts match hUGETracker 1.0.11 codegen (InstrumentToBytes / RenderGBDKInstrument) and the GBDK
 * structs in `hUGEDriver.h`:
 * - [tracks]: 64 rows x 3 bytes, the `dn` macro: `note | (instr & 0x10) << 3`, `(instr << 4) | effect`, `param`;
 *   note = driver note (cell note - [HugeRules.NOTE_OFFSET], 0 = C-3), 90 = no note.
 * - [duty]: `hUGEDutyInstr_t` = NR10, NR11, NR12, subpattern lo, hi (0), highmask (0x80 | lengthEnabled << 6).
 * - [wave]: `hUGEWaveInstr_t` = NR31, NR32, wave index, subpattern lo, hi (0), highmask (0x80 | lengthEnabled << 6).
 * - [noise]: `hUGENoiseInstr_t` = NR42, subpattern lo, hi (0), highmask (length & 63 | lengthEnabled << 6 |
 *   7-bit << 7), 0, 0.
 * - [waves]: 16 bytes per wave, two samples per byte, earlier sample in the high nibble.
 */
class HugeData(
    val ticksPerRow: Int,
    val loopOrder: Int,
    /** Song pattern index per order position (UI / playback-position mapping). */
    val orderPatterns: IntArray,
    /** Driver order1..order4: `orders[channel][position]` is an index into [tracks]; all the same length. */
    val orders: List<IntArray>,
    val tracks: List<ByteArray>,
    val duty: List<ByteArray>,
    val wave: List<ByteArray>,
    val noise: List<ByteArray>,
    val waves: List<ByteArray>,
) {
    val channels: Int get() = orders.size

    /** Driver `order_cnt` byte: orders * 2. */
    val orderCountByte: Int get() = orderPatterns.size * 2

    /** Stable track index of song [pattern] on [channel]; loop-variant tracks follow all of these. */
    fun track(pattern: Int, channel: Int): Int = pattern * channels + channel
}

object HugeLowering {
    const val ROW_BYTES = 3
    const val INSTRUMENT_BYTES = 6
    const val WAVE_BYTES = 16

    /**
     * Lowers [document]'s arrangement ([HugeRules.arranged]: parked orders left out), so order positions in the
     * result count played orders only.
     * @throws IllegalArgumentException with every [GbValidator.errors] message when it is not exportable.
     */
    fun lower(document: Song): HugeData {
        val errors = GbValidator.errors(document)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val song = HugeRules.arranged(document)
        val channels = song.patterns[0].tracks.size
        val tracks = ArrayList<ByteArray>(song.patterns.size * channels + channels)
        val played = song.patterns.map { HugeRules.playedTracks(it) }
        for (pattern in played) for (track in pattern) tracks += encodeTrack(track)
        val orders = List(channels) { ch -> IntArray(song.orders.size) { pos -> song.orders[pos] * channels + ch } }
        // The final order gets its own copy of any channel the loop jump changes; the length ending applies to it too.
        val last = song.orders.size - 1
        val finalTracks = HugeRules.finalOrderTracks(song, HugeRules.loopPlan(song))
        for (ch in 0 until channels) {
            if (finalTracks[ch] == played[song.orders[last]][ch]) continue
            orders[ch][last] = tracks.size
            tracks += encodeTrack(finalTracks[ch])
        }
        return HugeData(
            ticksPerRow = song.ticksPerRow,
            loopOrder = song.loopOrder,
            orderPatterns = song.orders.toIntArray(),
            orders = orders,
            tracks = tracks,
            duty = song.gb.pulse.map(::dutyBytes),
            wave = song.gb.wave.map(::waveBytes),
            noise = song.gb.noise.map(::noiseBytes),
            waves = song.gb.waves.map(::packWave),
        )
    }

    fun encodeTrack(cells: List<Cell>): ByteArray {
        val out = ByteArray(cells.size * ROW_BYTES)
        cells.forEachIndexed { row, cell ->
            val note = if (cell.note >= 0) cell.note - HugeRules.NOTE_OFFSET else HugeRules.NO_NOTE
            val effect = if (cell.note == Cell.CUT) HugeRules.FX_NOTE_CUT else cell.effect
            val param = if (cell.note == Cell.CUT) 0 else cell.param
            val i = row * ROW_BYTES
            out[i] = (note or ((cell.instrument and 0x10) shl 3)).toByte()
            out[i + 1] = (((cell.instrument shl 4) and 0xFF) or effect).toByte()
            out[i + 2] = param.toByte()
        }
        return out
    }

    /** NR10: sweep time << 4 | subtract (bit 3, hUGE "Down") | shift. */
    fun nr10(instrument: GbInstrument): Int =
        (instrument.sweepTime shl 4) or (if (instrument.sweepDirection == 0) 0x08 else 0) or instrument.sweepShift

    /** NRx2: initial volume << 4 | increase (bit 3) | pace. */
    fun envelope(instrument: GbInstrument): Int =
        (instrument.volume shl 4) or (instrument.envelopeDirection shl 3) or instrument.envelopePace

    fun dutyBytes(instrument: GbInstrument) = byteArrayOf(
        nr10(instrument).toByte(),
        ((instrument.duty shl 6) or (instrument.length and 0x3F)).toByte(),
        envelope(instrument).toByte(),
        0, 0,
        (0x80 or lengthBit(instrument)).toByte(),
    )

    fun waveBytes(instrument: GbInstrument) = byteArrayOf(
        instrument.length.toByte(),
        (instrument.waveVolume shl 5).toByte(),
        instrument.wave.toByte(),
        0, 0,
        (0x80 or lengthBit(instrument)).toByte(),
    )

    fun noiseBytes(instrument: GbInstrument) = byteArrayOf(
        envelope(instrument).toByte(),
        0, 0,
        ((instrument.length and 0x3F) or lengthBit(instrument) or (instrument.noiseWidth shl 7)).toByte(),
        0, 0,
    )

    fun packWave(samples: List<Int>) = ByteArray(WAVE_BYTES) { i -> ((samples[2 * i] shl 4) or samples[2 * i + 1]).toByte() }

    private fun lengthBit(instrument: GbInstrument) = if (instrument.lengthEnabled) 0x40 else 0
}
