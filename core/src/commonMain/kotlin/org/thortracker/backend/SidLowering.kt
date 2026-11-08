package org.thortracker.backend

import org.thortracker.backend.SidPlayerImage as P

/**
 * A [GtSong] packed into the `"c64-sid"` engine's image (docs/AUDIO_ENGINE.md, SID "Image layout"): GoatTracker 2.77's
 * packed playroutine data exactly as its packer (`greloc.c`, "Disable optimization") writes it — instrument order,
 * table conversions, orderlists and `packpattern` — at the fixed addresses of the Thor layout. Port of
 * `tools/audio/sid/gt_song.py` (`lower`), which is proven byte-identical to GoatTracker's packer; `SidGoldenTest`
 * proves this port equals it. Every instrument and table row is kept (the packer drops unreachable ones), so row and
 * instrument numbers need no remapping beyond the instrument order.
 */
object SidLowering {
    private const val FX = 0x40
    private const val FXONLY = 0x50
    private const val FIRSTNOTE = 0x60
    private const val CMD_SETMASTERVOL = 13
    private const val CMD_SETTEMPO = 15
    private val INSTR_COLUMNS = intArrayOf(
        P.MT_INSAD, P.MT_INSSR, P.MT_INSWAVEPTR, P.MT_INSPULSEPTR, P.MT_INSFILTPTR,
        P.MT_INSVIBPARAM, P.MT_INSVIBDELAY, P.MT_INSGATETIMER, P.MT_INSFIRSTWAVE,
    )
    private val TABLE_LEFT = intArrayOf(P.MT_WAVETBL, P.MT_PULSETIMETBL, P.MT_FILTTIMETBL, P.MT_SPEEDLEFTTBL)
    private val TABLE_RIGHT = intArrayOf(P.MT_NOTETBL, P.MT_PULSESPDTBL, P.MT_FILTSPDTBL, P.MT_SPEEDRIGHTTBL)

    /** What [lower] produced besides the image. */
    class Lowered(
        /** Player number of each GoatTracker instrument (index = instrument number, 0 unused). */
        val playerNumber: IntArray,
        val firstNoHr: Int,
        val firstLegato: Int,
        /** Address after the last song byte; `.sid`/`.prg` exports stop there. */
        val dataEnd: Int,
    )

    /**
     * greloc.c's instrument order: hard-restart instruments, then no-hard-restart (`$80`), then legato (`$40`), each by
     * number. GoatTracker's packer counts one more no-hard-restart instrument in multispeed (its first legato
     * instrument then plays without legato); the engine's operands are ours, so this lowering keeps legato exact.
     */
    fun instrumentOrder(song: GtSong): Triple<IntArray, Int, Int> {
        val groups = List(3) { ArrayList<Int>() }
        song.instruments.forEachIndexed { i, ins ->
            groups[if (ins.gateTimer and 0x40 != 0) 2 else if (ins.gateTimer and 0x80 != 0) 1 else 0] += i + 1
        }
        val map = IntArray(song.instruments.size + 1)
        (groups[0] + groups[1] + groups[2]).forEachIndexed { k, n -> map[n] = k + 1 }
        return Triple(map, groups[0].size + 1, groups[0].size + groups[1].size + 1)
    }

    /** greloc.c `packpattern()` without optimizations; instruments renumbered by [playerNumber]. */
    fun packPattern(rows: List<GtRow>, playerNumber: IntArray): ByteArray {
        val temp = ArrayList<Int>(rows.size * 4)
        var instr = 0
        var command = -1
        var databyte = -1
        rows.forEachIndexed { c, row ->
            var ins = row.instrument
            if (c != 0 && ins != 0 && ins == instr) ins = 0 else if (ins != 0) instr = ins
            var cmd = row.command
            var param = row.param
            if (cmd == CMD_SETMASTERVOL && param > 0x0F) { cmd = 0; param = 0 } // timing marks need author info
            if (cmd == CMD_SETTEMPO && (param and 0x7F) >= 3) param -= 1
            if (ins != 0) temp += playerNumber[ins]
            val changed = cmd != command || param != databyte
            if (changed) { command = cmd; databyte = param }
            if (row.note == GtSong.REST) {
                if (changed) { temp += FXONLY + cmd; if (cmd != 0) temp += param } else temp += GtSong.REST
            } else {
                if (changed) { temp += FX + cmd; if (cmd != 0) temp += param }
                temp += row.note
            }
        }
        val dest = Bytes()
        var c = 0
        val n = temp.size
        while (c < n) {
            var packOk = c != 0
            if (temp[c] < FX) { dest.byte(temp[c++]); packOk = false }
            if (temp[c] in FXONLY until FIRSTNOTE) {
                val fx = temp[c] - FXONLY
                dest.byte(temp[c++])
                if (fx != 0) dest.byte(temp[c++])
                continue
            }
            if (temp[c] < FXONLY) {
                val fx = temp[c] - FX
                dest.byte(temp[c++])
                if (fx != 0) dest.byte(temp[c++])
                packOk = false
            }
            if (temp[c] != GtSong.REST) packOk = false
            if (!packOk) dest.byte(temp[c++])
            else {
                var d = c
                while (d < n && temp[d] == GtSong.REST) {
                    d++
                    if (d - c == 64) break
                }
                d -= c
                if (d > 1) { dest.byte((-d) and 0xFF); c += d } else dest.byte(temp[c++])
            }
        }
        require(dest.size <= 256) { "pattern too complex: ${dest.size} bytes packed, GoatTracker's limit is 256" }
        if (dest.size < 256) dest.byte(0)
        return dest.toByteArray()
    }

    /**
     * Writes the image of [song] into [out] ([SidPlayerImage.IMAGE_SIZE] bytes, zeroed here).
     * @throws IllegalArgumentException naming what does not fit the layout.
     */
    fun lower(song: GtSong, out: ByteArray): Lowered {
        require(out.size >= P.IMAGE_SIZE) { "image buffer too small" }
        out.fill(0, 0, P.IMAGE_SIZE)
        val orders = song.orderlists[0].size
        require(song.orderlists.all { it.size == orders }) { "every voice needs the same number of orders" }
        require(song.orderlists.all { ol -> ol.all { it < GtSong.REPEAT } }) { "orderlists hold pattern numbers only" }
        require(orders in 1..GtSong.MAX_SONGLEN) { "The song has $orders orders; GoatTracker plays 1-${GtSong.MAX_SONGLEN}" }
        require(song.restart in 0 until orders) { "the loop order is outside the song" }
        require(song.instruments.size in 1..P.MAX_INSTRUMENTS) { "1-${P.MAX_INSTRUMENTS} instruments" }
        require(song.tempo in 2..127) { "The start tempo must be 3-128 ticks per row" }
        val (map, firstNoHr, firstLegato) = instrumentOrder(song)

        fun poke(addr: Int, v: Int) { out[P.HEADER_SIZE + addr - P.REGION_BASE] = v.toByte() }
        for (i in 0 until 96) { poke(P.MT_FREQTBLLO + i, song.freqLo[i]); poke(P.MT_FREQTBLHI + i, song.freqHi[i]) }
        song.instruments.forEachIndexed { i, ins ->
            val k = map[i + 1] - 1
            val vib = if (ins.vibDelay != 0) ins.vibParam to ins.vibDelay - 1 else 0 to 0
            val cols = intArrayOf(ins.ad, ins.sr, ins.wave, ins.pulse, ins.filter, vib.first, vib.second, ins.gateTimer and 0x3F, ins.firstWave)
            for (col in cols.indices) poke(INSTR_COLUMNS[col] + k, cols[col])
        }
        for (t in 0 until 4) {
            val left = song.left[t]
            val right = song.right[t]
            require(left.size <= 255 && right.size == left.size) { "table $t has ${left.size} rows" }
            for (d in left.indices) {
                val l = left[d]
                val r = right[d]
                when (t) {
                    0 -> {
                        var w = l
                        if (l in 0xE0..0xEF) w = w and 0x0F
                        if (l in 0x10..0xEF) w += 0x10
                        poke(TABLE_LEFT[t] + d, w)
                        poke(TABLE_RIGHT[t] + d, if (l in 0xF0..0xFE || l == 0xFF) r else r xor 0x80)
                    }
                    2 -> {
                        poke(TABLE_LEFT[t] + d, if (l != 0xFF && l > 0x80) ((l and 0x70) shr 1) or 0x80 else l)
                        poke(TABLE_RIGHT[t] + d, r)
                    }
                    else -> { poke(TABLE_LEFT[t] + d, l); poke(TABLE_RIGHT[t] + d, r) }
                }
            }
        }
        // Patterns the orderlists use, numbered in pattern-number order (greloc's pattmap).
        val used = song.orderlists.flatten().distinct().sorted()
        require(used.size <= P.MAX_PATTERNS) { "The song has ${used.size} voice patterns; GoatTracker holds ${P.MAX_PATTERNS}" }
        val pattMap = HashMap<Int, Int>().apply { used.forEachIndexed { k, p -> put(p, k) } }
        var heap = P.HEAP_BASE
        fun room(n: Int, what: String) = require(heap + n <= P.REGION_END) {
            "Song data does not fit: $what would end at \$${(heap + n).toString(16).uppercase()}; the C64 player's song region " +
                "ends at \$${P.REGION_END.toString(16).uppercase()} (${P.REGION_END - P.HEAP_BASE} bytes for orders and patterns). " +
                "Reuse patterns or shorten the song"
        }
        for (c in 0 until 3) {
            poke(P.MT_SONGTBLLO + c, heap and 0xFF)
            poke(P.MT_SONGTBLHI + c, heap shr 8)
            room(orders + 2, "the orderlists")
            for (p in song.orderlists[c]) poke(heap++, pattMap.getValue(p))
            poke(heap++, GtSong.LOOPSONG)
            poke(heap++, song.restart)
        }
        for (p in used) {
            val rows = song.patterns[p]
            require(rows.size in 1..P.MAX_ROWS) { "a voice pattern has ${rows.size} rows; GoatTracker's are 1-${P.MAX_ROWS}" }
            val packed = packPattern(rows, map)
            val k = pattMap.getValue(p)
            poke(P.MT_PATTTBLLO + k, heap and 0xFF)
            poke(P.MT_PATTTBLHI + k, heap shr 8)
            room(packed.size, "pattern ${k}")
            for (b in packed) poke(heap++, b.toInt() and 0xFF)
        }
        "TTSD".encodeToByteArray().copyInto(out, 0)
        le32(out, 4, P.PLAYER_ID)
        le16(out, 8, P.REGION_BASE)
        le16(out, P.HEADER_DATA_END, heap)
        out[P.HEADER_LAYOUT] = P.LAYOUT_THOR.toByte()
        out[P.HEADER_MODEL] = song.model.toByte()
        out[P.HEADER_MULTIPLIER] = song.multiplier.toByte()
        out[P.HEADER_TEMPO] = song.tempo.toByte()
        out[P.HEADER_FIRSTNOHR] = firstNoHr.toByte()
        out[P.HEADER_FIRSTLEGATO] = firstLegato.toByte()
        out[P.HEADER_HR_AD] = song.hrAd.toByte()
        out[P.HEADER_HR_SR] = song.hrSr.toByte()
        out[P.HEADER_INSTRUMENTS] = song.instruments.size.toByte()
        for (n in 1 until map.size) out[P.HEADER_SLOT_MAP + n] = map[n].toByte()
        return Lowered(map, firstNoHr, firstLegato, heap)
    }

    private fun le16(b: ByteArray, at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, at: Int, v: Int) { le16(b, at, v and 0xFFFF); le16(b, at + 2, v ushr 16) }
}
