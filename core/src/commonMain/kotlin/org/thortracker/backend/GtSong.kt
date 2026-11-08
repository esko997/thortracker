package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.sid.SidRules
import org.thortracker.system.sid.SidChip
import org.thortracker.system.sid.SidSpeeds
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.sid.SidTables
import org.thortracker.system.sid.SidTuning
import org.thortracker.system.sid.sid

/** One GoatTracker instrument as its song file stores it (9 bytes and a 16-character name). */
data class GtInstrument(
    val ad: Int, val sr: Int, val wave: Int, val pulse: Int, val filter: Int,
    val vibParam: Int, val vibDelay: Int, val gateTimer: Int, val firstWave: Int, val name: String,
)

/** One GoatTracker pattern row: note byte (`$60+n`, [REST], [KEYOFF], [KEYON]), instrument, command, parameter. */
data class GtRow(val note: Int, val instrument: Int = 0, val command: Int = 0, val param: Int = 0)

/**
 * A GoatTracker 2 song (subtune 0) in GoatTracker's own terms: table rows and row-number parameters, as its editor and
 * `.sng` file ("GTS5") hold them, plus what the packer is told on its command line ([model], [multiplier], the
 * hard-restart envelope) and the frequency table. [tempo] is the player's `DEFAULTTEMPO` (ticks per row − 1).
 *
 * [of] builds it from a Thor Tracker SID song (table placement, speed rows, voice patterns); [SidLowering] packs it as
 * GoatTracker's packer does; [sng] / [read] are the song file. Instrument n is Thor slot n.
 */
class GtSong(
    val name: String,
    val author: String,
    val copyright: String,
    /** Per voice: pattern numbers, one per order (the Thor shape: same count on every voice). */
    val orderlists: List<List<Int>>,
    val restart: Int,
    val instruments: List<GtInstrument>,
    /** Per table (wave, pulse, filter, speed): left and right columns, rows 1..n at index 0..n-1. */
    val left: List<IntArray>,
    val right: List<IntArray>,
    val patterns: List<List<GtRow>>,
    val tempo: Int,
    val model: Int = MODEL_8580,
    val multiplier: Int = 1,
    val hrAd: Int = 0x0F,
    val hrSr: Int = 0x00,
    val freqLo: IntArray = DEFAULT_FREQ_LO.copyOf(),
    val freqHi: IntArray = DEFAULT_FREQ_HI.copyOf(),
) {
    /**
     * GoatTracker's song file. GoatTracker reads the start tempo from instrument 63 when it is a "tempo holder" (attack/
     * decay = ticks, no wave program); a song whose tempo is not GoatTracker's default (6 × multispeed) gets one there.
     * @throws IllegalArgumentException when instrument 63 is played, so it cannot carry the tempo.
     */
    fun sng(): ByteArray {
        val out = Bytes()
        out.bytes("GTS5".encodeToByteArray())
        for (t in listOf(name, author, copyright)) out.bytes(text(t, 32))
        out.byte(1)
        for (c in 0 until 3) {
            out.byte(orderlists[c].size + 1)
            for (p in orderlists[c]) out.byte(p)
            out.byte(LOOPSONG)
            out.byte(restart)
        }
        val ins = instruments.toMutableList()
        if (tempo != defaultTempo(multiplier)) {
            require(ins.size < TEMPO_SLOT || patterns.none { p -> p.any { it.instrument == TEMPO_SLOT } }) {
                "GoatTracker files carry the start tempo in instrument 3F, which the song plays; move that instrument to another " +
                    "slot or start the song with an Fxx effect at GoatTracker's default tempo (${6 * multiplier} ticks per row)"
            }
            while (ins.size < TEMPO_SLOT) ins += EMPTY_INSTRUMENT
            ins[TEMPO_SLOT - 1] = GtInstrument(tempo + 1, 0, 0, 0, 0, 0, 0, 0, 0, "Tempo")
        }
        out.byte(ins.size)
        for (i in ins) {
            for (b in intArrayOf(i.ad, i.sr, i.wave, i.pulse, i.filter, i.vibParam, i.vibDelay, i.gateTimer, i.firstWave)) out.byte(b)
            out.bytes(text(i.name, 16))
        }
        for (t in 0 until 4) {
            out.byte(left[t].size)
            for (b in left[t]) out.byte(b)
            for (b in right[t]) out.byte(b)
        }
        out.byte(patterns.size)
        for (p in patterns) {
            out.byte(p.size + 1)
            for (r in p) { out.byte(r.note); out.byte(r.instrument); out.byte(r.command); out.byte(r.param) }
            out.byte(ENDPATT); out.byte(0); out.byte(0); out.byte(0)
        }
        return out.toByteArray()
    }

    companion object {
        // GoatTracker 2 constants (src/gcommon.h)
        const val MAX_PATTERNS = 208
        const val MAX_SONGLEN = 254
        const val LOOPSONG = 0xFF
        const val ENDPATT = 0xFF
        const val REPEAT = 0xD0
        const val FIRSTNOTE = 0x60
        const val REST = 0xBD
        const val KEYOFF = 0xBE
        const val KEYON = 0xBF
        const val MODEL_6581 = 0
        const val MODEL_8580 = 1

        /** The instrument GoatTracker reads a song's start tempo from. */
        const val TEMPO_SLOT = 63

        private val EMPTY_INSTRUMENT = GtInstrument(0, 0, 0, 0, 0, 0, 0, 0, 0, "")

        /** GoatTracker's default frequency table (src/gplay.c): note 0 = C-0, 57 = A-4 = `$1D46`. */
        val DEFAULT_FREQ_LO = intArrayOf(
            0x17, 0x27, 0x39, 0x4b, 0x5f, 0x74, 0x8a, 0xa1, 0xba, 0xd4, 0xf0, 0x0e,
            0x2d, 0x4e, 0x71, 0x96, 0xbe, 0xe8, 0x14, 0x43, 0x74, 0xa9, 0xe1, 0x1c,
            0x5a, 0x9c, 0xe2, 0x2d, 0x7c, 0xcf, 0x28, 0x85, 0xe8, 0x52, 0xc1, 0x37,
            0xb4, 0x39, 0xc5, 0x5a, 0xf7, 0x9e, 0x4f, 0x0a, 0xd1, 0xa3, 0x82, 0x6e,
            0x68, 0x71, 0x8a, 0xb3, 0xee, 0x3c, 0x9e, 0x15, 0xa2, 0x46, 0x04, 0xdc,
            0xd0, 0xe2, 0x14, 0x67, 0xdd, 0x79, 0x3c, 0x29, 0x44, 0x8d, 0x08, 0xb8,
            0xa1, 0xc5, 0x28, 0xcd, 0xba, 0xf1, 0x78, 0x53, 0x87, 0x1a, 0x10, 0x71,
            0x42, 0x89, 0x4f, 0x9b, 0x74, 0xe2, 0xf0, 0xa6, 0x0e, 0x33, 0x20, 0xff,
        )
        val DEFAULT_FREQ_HI = intArrayOf(
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02,
            0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x03, 0x03, 0x03, 0x03, 0x03, 0x04,
            0x04, 0x04, 0x04, 0x05, 0x05, 0x05, 0x06, 0x06, 0x06, 0x07, 0x07, 0x08,
            0x08, 0x09, 0x09, 0x0a, 0x0a, 0x0b, 0x0c, 0x0d, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x17, 0x18, 0x1a, 0x1b, 0x1d, 0x1f, 0x20,
            0x22, 0x24, 0x27, 0x29, 0x2b, 0x2e, 0x31, 0x34, 0x37, 0x3a, 0x3e, 0x41,
            0x45, 0x49, 0x4e, 0x52, 0x57, 0x5c, 0x62, 0x68, 0x6e, 0x75, 0x7c, 0x83,
            0x8b, 0x93, 0x9c, 0xa5, 0xaf, 0xb9, 0xc4, 0xd0, 0xdd, 0xea, 0xf8, 0xff,
        )

        /** greloc.c's `DEFAULTTEMPO` without a tempo-holder instrument: 6 ticks per row × the multiplier, minus one. */
        fun defaultTempo(multiplier: Int): Int = multiplier * 6 - 1

        /**
         * The GoatTracker song a Thor Tracker SID song is ([document]'s arrangement): programs at their placed rows,
         * jumps and program commands as table rows, speed effects as speedtable rows ([SidSpeeds]), one GoatTracker
         * pattern per distinct voice pattern of each order. A voice whose order starts with a note but no instrument gets
         * the instrument it plays in straight play written there, so the order sounds the same when entered by the
         * loop, a seek or a pattern loop. The song has no "released" text, so GoatTracker's copyright field stays empty.
         */
        fun of(document: Song): GtSong {
            val song = SidSystem.arranged(document)
            val sid = song.sid
            val placed = (SidTables.WAVE..SidTables.FILTER).map { SidTables.placement(sid, it) }
            require(placed.all { it.overflow == 0 }) { "The instrument programs do not fit GoatTracker's tables" }
            val speeds = SidSpeeds.of(song)
            require(speeds.size <= SidTables.ROWS) { "The song uses ${speeds.size} speeds; GoatTracker's speedtable has ${SidTables.ROWS} rows" }
            val speedRow = HashMap<Int, Int>().apply { speeds.forEachIndexed { i, v -> put(v, i + 1) } }

            fun gtParam(command: Int, param: Int): Int {
                val table = SidRules.programTable(command)
                return when {
                    table >= 0 -> if (param == 0) 0 else placed[table].start[param]
                    command in SidRules.FX_PORTA_UP..SidRules.FX_VIBRATO || command == SidRules.FX_FUNKTEMPO ->
                        SidRules.speedOf(command, param).let { if (it < 0) 0 else speedRow.getValue(it) }
                    else -> param
                }
            }

            // Tables: placed programs with jumps and wave commands made absolute.
            val left = ArrayList<IntArray>()
            val right = ArrayList<IntArray>()
            for (t in SidTables.WAVE..SidTables.FILTER) {
                val p = placed[t]
                val n = p.owner.indexOfLast { it != 0 }.coerceAtLeast(0)
                val l = IntArray(n)
                val r = IntArray(n)
                for (row in 1..n) {
                    val slot = p.owner[row]
                    if (slot == 0) continue
                    val rows = sid.instruments[slot - 1].program(t).rows
                    val i = p.index[row]
                    val src = rows.getOrNull(i)
                    val at = p.start[slot]
                    when {
                        src == null -> { l[row - 1] = SidTables.JUMP; r[row - 1] = 0 } // the stop row the lowering adds
                        src.isJump -> { l[row - 1] = SidTables.JUMP; r[row - 1] = if (src.right == 0) 0 else at + src.right - 1 }
                        t == SidTables.WAVE && src.left in SidTables.WAVE_COMMAND until SidTables.JUMP -> {
                            l[row - 1] = src.left
                            r[row - 1] = gtParam(src.left - SidTables.WAVE_COMMAND, src.right)
                        }
                        else -> { l[row - 1] = src.left; r[row - 1] = src.right }
                    }
                }
                left += l
                right += r
            }
            left += IntArray(speeds.size) { speeds[it] shr 8 }
            right += IntArray(speeds.size) { speeds[it] and 0xFF }

            val instruments = sid.instruments.mapIndexed { i, ins ->
                val slot = i + 1
                val vib = SidSpeeds.vibratoValue(ins)
                GtInstrument(
                    ins.attackDecay, ins.sustainRelease,
                    placed[SidTables.WAVE].start[slot], placed[SidTables.PULSE].start[slot], placed[SidTables.FILTER].start[slot],
                    vib?.let { speedRow.getValue(it) } ?: 0, if (vib != null) ins.vibrato.delay else 0,
                    ins.gateTimerByte, ins.firstWave, ins.name,
                )
            }

            // Voice patterns: one per distinct content, numbered in order of first use (order, then voice).
            val patterns = ArrayList<List<GtRow>>()
            val index = HashMap<List<GtRow>, Int>()
            val orderlists = List(3) { ArrayList<Int>() }
            val current = IntArray(3)
            for (o in song.orders.indices) {
                val pattern = song.patterns[song.orders[o]]
                for (c in 0 until 3) {
                    val track = pattern.tracks[c]
                    var entering = current[c]
                    val rows = ArrayList<GtRow>(pattern.length)
                    for (r in 0 until pattern.length) {
                        val cell = track[r]
                        var instrument = cell.instrument
                        if (instrument != 0) entering = 0
                        else if (cell.note >= 0 && entering != 0) { instrument = entering; entering = 0 }
                        if (cell.instrument != 0) current[c] = cell.instrument
                        rows += GtRow(noteByte(cell.note), instrument, cell.effect, if (cell.effect == 0) 0 else gtParam(cell.effect, cell.param))
                    }
                    orderlists[c] += index.getOrPut(rows) { patterns += rows; patterns.size - 1 }
                }
            }
            require(patterns.size <= MAX_PATTERNS) {
                "The song has ${patterns.size} different voice patterns; GoatTracker holds $MAX_PATTERNS. Reuse patterns or shorten the song"
            }
            val freq = SidTuning.frequencies(song)
            return GtSong(
                song.title, song.artist, "", orderlists, song.loopOrder, instruments,
                left, right, patterns, song.ticksPerRow - 1,
                if (sid.chip == SidChip.MOS6581) MODEL_6581 else MODEL_8580, sid.multispeed,
                sid.hardRestartAttackDecay, sid.hardRestartSustainRelease,
                IntArray(96) { freq[it] and 0xFF }, IntArray(96) { freq[it] shr 8 },
            )
        }

        /** GoatTracker's note byte for a Thor cell note. */
        fun noteByte(note: Int): Int = when {
            note >= 0 -> FIRSTNOTE + note
            note == Cell.CUT -> KEYOFF
            note == SidRules.KEY_ON -> KEYON
            else -> REST
        }

        /**
         * Reads a GoatTracker 2 song file (`GTS5`, subtune 0). The start tempo follows greloc.c (instrument 63 as tempo
         * holder); the model, multiplier and hard-restart envelope are not in the file and take GoatTracker's defaults.
         */
        fun read(data: ByteArray, model: Int = MODEL_6581, multiplier: Int = 1): GtSong {
            var pos = 0
            fun take(n: Int): IntArray {
                require(pos + n <= data.size) { "The GoatTracker song is truncated" }
                return IntArray(n) { data[pos + it].toInt() and 0xFF }.also { pos += n }
            }
            fun str(n: Int): String = take(n).takeWhile { it != 0 }.map { it.toChar() }.toCharArray().concatToString()
            require(data.size >= 4 && data.decodeToString(0, 4) == "GTS5") { "Not a GoatTracker 2 song (GTS5)" }
            pos = 4
            val name = str(32)
            val author = str(32)
            val copyright = str(32)
            val subtunes = take(1)[0]
            val orderlists = List(3) { ArrayList<Int>() }
            var restart = 0
            for (t in 0 until subtunes) for (c in 0 until 3) {
                val n = take(1)[0]
                val ol = take(n + 1)
                if (t != 0) continue
                val end = ol.indexOf(LOOPSONG)
                require(end >= 0) { "Orderlist without end mark" }
                orderlists[c] += ol.take(end)
                restart = ol[end + 1]
            }
            val instruments = List(take(1)[0]) {
                val b = take(9)
                GtInstrument(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], str(16))
            }
            val left = ArrayList<IntArray>()
            val right = ArrayList<IntArray>()
            repeat(4) {
                val n = take(1)[0]
                left += take(n)
                right += take(n)
            }
            val patterns = List(take(1)[0]) {
                val n = take(1)[0]
                val rows = take(4 * n)
                (0 until n).map { GtRow(rows[4 * it], rows[4 * it + 1], rows[4 * it + 2], rows[4 * it + 3]) }.takeWhile { it.note != ENDPATT }
            }
            val holder = instruments.getOrNull(TEMPO_SLOT - 1)
            val tempo = if (holder != null && holder.ad >= 2 && holder.wave == 0) holder.ad - 1 else defaultTempo(multiplier)
            return GtSong(name, author, copyright, orderlists, restart, instruments, left, right, patterns, tempo, model, multiplier)
        }

        private fun text(s: String, n: Int): ByteArray = ByteArray(n).also { b ->
            s.take(n).forEachIndexed { i, ch -> b[i] = (if (ch.code in 0x20..0xFF) ch.code else '?'.code).toByte() }
        }
    }
}
