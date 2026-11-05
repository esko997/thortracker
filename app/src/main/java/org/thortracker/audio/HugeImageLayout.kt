package org.thortracker.audio

import org.thortracker.backend.HugeData
import org.thortracker.backend.HugeLimits
import org.thortracker.backend.HugeLowering

/**
 * Places a lowered song ([HugeData], the same bytes `.uge` export is built from) into the
 * 64 KiB address space of the preview machine (app/src/main/cpp/huge_machine.h). The native
 * side validates every pointer again and only copies bytes, so this is the one place that
 * decides addresses.
 *
 * Fixed header (addresses never move, so live edits only rewrite bytes):
 * - 0x0900 song descriptor (`hUGESong_t`: tempo, &order_cnt, order1..4, duty, wave, noise,
 *   routines = NULL, waves)
 * - 0x0915 order_cnt (orders * 2)
 * - 0x0920 / 0x0980 / 0x09E0 duty / wave / noise instrument tables (15 x 6 bytes)
 * - 0x0A40 waves (16 x 16 bytes)
 * - 0x0B40 four order tables, 256 bytes each (127 orders max)
 * - 0x0F40..0xBFFF and 0xC400..0xDFFF: 192-byte tracks, byte-identical tracks shared.
 */
object HugeImageLayout {
    const val SIZE = 0x10000
    const val DESCRIPTOR = 0x0900
    private const val ORDER_COUNT = 0x0915
    private const val DUTY = 0x0920
    private const val WAVE = 0x0980
    private const val NOISE = 0x09E0
    private const val WAVES = 0x0A40
    private const val ORDER_TABLES = 0x0B40
    private const val ORDER_TABLE_BYTES = 0x100
    private const val TRACKS_A = 0x0F40
    private const val REGION_A_END = 0xC000
    private const val REGION_B = 0xC400
    private const val REGION_B_END = 0xE000
    const val TRACK_BYTES = 64 * HugeLowering.ROW_BYTES
    private const val SLOTS_A = (REGION_A_END - TRACKS_A) / TRACK_BYTES
    private const val SLOTS_B = (REGION_B_END - REGION_B) / TRACK_BYTES
    const val TRACK_SLOTS = SLOTS_A + SLOTS_B

    private class Key(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && other.bytes.contentEquals(bytes)
    }

    /**
     * Writes the image of [data] into [out] (exactly [SIZE] bytes, fully overwritten).
     * @throws IllegalArgumentException if the song does not fit the preview player.
     */
    fun build(data: HugeData, out: ByteArray) {
        check(TRACK_SLOTS == HugeLimits.PREVIEW_TRACK_SLOTS) { "preview layout and HugeLimits disagree" }
        require(out.size == SIZE)
        val orders = data.orderPatterns.size
        require(orders in 1..HugeLimits.MAX_ORDERS) { "Song needs 1..${HugeLimits.MAX_ORDERS} orders (has $orders)" }
        require(data.orders.size == 4 && data.orders.all { it.size == orders }) { "GB songs have four equal order lists" }
        require(data.ticksPerRow in 1..255) { "Ticks per row must be 1..255" }
        out.fill(0)

        out[DESCRIPTOR] = data.ticksPerRow.toByte()
        put16(out, DESCRIPTOR + 1, ORDER_COUNT)
        for (ch in 0 until 4) put16(out, DESCRIPTOR + 3 + 2 * ch, ORDER_TABLES + ch * ORDER_TABLE_BYTES)
        put16(out, DESCRIPTOR + 11, DUTY)
        put16(out, DESCRIPTOR + 13, WAVE)
        put16(out, DESCRIPTOR + 15, NOISE)
        put16(out, DESCRIPTOR + 17, 0) // routines: 6xx is rejected by validation
        put16(out, DESCRIPTOR + 19, WAVES)
        out[ORDER_COUNT] = (orders * 2).toByte()

        copyTable(data.duty, out, DUTY, HugeLowering.INSTRUMENT_BYTES, 15)
        copyTable(data.wave, out, WAVE, HugeLowering.INSTRUMENT_BYTES, 15)
        copyTable(data.noise, out, NOISE, HugeLowering.INSTRUMENT_BYTES, 15)
        copyTable(data.waves, out, WAVES, HugeLowering.WAVE_BYTES, 16)

        val slots = HashMap<Key, Int>()
        for (pos in 0 until orders) {
            for (ch in 0 until 4) {
                val track = data.tracks[data.orders[ch][pos]]
                require(track.size == TRACK_BYTES) { "Track must be $TRACK_BYTES bytes" }
                val key = Key(track)
                val slot = slots[key] ?: slots.size.also { slot ->
                    require(slot < TRACK_SLOTS) {
                        "Song uses more than $TRACK_SLOTS distinct channel patterns; the GB preview limit is $TRACK_SLOTS"
                    }
                    slots[key] = slot
                    track.copyInto(out, slotAddress(slot))
                }
                put16(out, ORDER_TABLES + ch * ORDER_TABLE_BYTES + pos * 2, slotAddress(slot))
            }
        }
    }

    private fun slotAddress(slot: Int) =
        if (slot < SLOTS_A) TRACKS_A + slot * TRACK_BYTES else REGION_B + (slot - SLOTS_A) * TRACK_BYTES

    private fun copyTable(rows: List<ByteArray>, out: ByteArray, base: Int, width: Int, count: Int) {
        require(rows.size == count) { "Expected $count entries, got ${rows.size}" }
        rows.forEachIndexed { i, bytes ->
            require(bytes.size == width) { "Entry $i must be $width bytes" }
            bytes.copyInto(out, base + i * width)
        }
    }

    private fun put16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
