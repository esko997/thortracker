package org.thortracker.system.sid

import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SharedState
import org.thortracker.system.SongSetting

/** The SID song settings the Song view lists: chip model, multispeed and the hard-restart envelope. */
object SidSongSettings {
    private fun with(song: Song, f: (SidSound) -> SidSound): Song = f(song.sid).let { if (it == song.sid) song else song.copy(sound = it) }

    private fun byte(v: Int) = NumberText.hex(v, 2)

    val ALL: List<SongSetting> = listOf(
        SongSetting("Chip", "Chip model", "Set chip model",
            value = { s ->
                when (s.sid.chip) {
                    SidChip.MOS8580 -> "MOS 8580   clean, consistent filter (recommended)"
                    SidChip.MOS6581 -> "MOS 6581   warmer, louder combined waves, chip-dependent filter"
                }
            },
            adjust = { s, f, c -> if (f + c == 0) s else with(s) { it.copy(chip = SidChip.entries[(it.chip.ordinal + f + c).mod(SidChip.entries.size)]) } },
            reset = { s -> with(s) { it.copy(chip = SidChip.MOS8580) } },
        ),
        SongSetting("Multispeed", "Player calls per frame", "Set multispeed",
            value = { s ->
                val m = s.sid.multispeed
                if (m == 1) "1×   one player call per 50 Hz frame"
                else "$m×   ${NumberText.fixed(SidSystem.tickHz * m, 0)} ticks/s: tempos and gate timers count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + 4 * c).coerceIn(SidSound.MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ),
        SongSetting("HR attack", "Hard restart attack/decay", "Set hard restart",
            value = { s ->
                val ad = s.sid.hardRestartAttackDecay
                "${byte(ad)}   attack ${NumberText.hex(ad shr 4)}, decay ${NumberText.hex(ad and 15)} before each hard-restart note"
            },
            adjust = { s, f, c -> with(s) { it.copy(hardRestartAttackDecay = (it.hardRestartAttackDecay + f + 16 * c).coerceIn(0, 0xFF)) } },
            reset = { s -> with(s) { it.copy(hardRestartAttackDecay = 0x0F) } },
        ),
        SongSetting("HR sustain", "Hard restart sustain/release", "Set hard restart",
            value = { s ->
                val sr = s.sid.hardRestartSustainRelease
                "${byte(sr)}   sustain ${NumberText.hex(sr shr 4)}, release ${NumberText.hex(sr and 15)} (GoatTracker default 0F00)"
            },
            adjust = { s, f, c -> with(s) { it.copy(hardRestartSustainRelease = (it.hardRestartSustainRelease + f + 16 * c).coerceIn(0, 0xFF)) } },
            reset = { s -> with(s) { it.copy(hardRestartSustainRelease = 0x00) } },
        ),
    )
}

/**
 * Who owns the SID's one filter at a song position, as straight play reaches it: GoatTracker gives the filter to the
 * last voice that started a filter program, by an instrument with one on a note or by `Axx`; `B00` turns it off.
 * Rows are taken in play order (orders that play, then rows, voices 1-3 within a row).
 */
object SidFilterOwner {
    /** A voice took the filter: [voice] 0..2 by instrument [slot]'s program at ([order], [row]); [off]: `B00` turned it off. */
    data class Owner(val voice: Int, val slot: Int, val order: Int, val row: Int, val off: Boolean)

    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /** The filter's owner when row [row] of order [order] plays, or null when nothing has taken it yet. */
    fun at(song: Song, order: Int, row: Int): Owner? {
        if (order !in song.orders.indices) return null
        val ins = song.sid.instruments
        var owner: Owner? = null
        // The orders straight play reaches before [order]; a parked order plays only on its own.
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns[song.orders[o]]
            val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
            for (r in 0..last) for (ch in 0 until minOf(3, p.tracks.size)) {
                val cell = p.tracks[ch][r]
                if (cell.note >= 0 && cell.instrument > 0 && ins.getOrNull(cell.instrument - 1)?.filter?.rows?.isNotEmpty() == true) {
                    owner = Owner(ch, cell.instrument, o, r, false)
                }
                if (cell.hasEffect && cell.effect == SidRules.FX_FILTER_PROGRAM && cell.param > 0) owner = Owner(ch, cell.param, o, r, false)
                if (cell.hasEffect && cell.effect == SidRules.FX_FILTER_CONTROL && cell.param == 0) owner = Owner(ch, 0, o, r, true)
            }
        }
        return owner
    }

    /** [at] as the Pattern view shows it: a tag on the owning voice and an inspector line. Cached per position. */
    fun state(song: Song, order: Int, row: Int): SharedState {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val o = at(song, order, row)
        val tags = List<String?>(3) { if (o != null && !o.off && it == o.voice) "FILTER" else null }
        val line = when {
            o == null -> "Filter: no voice has started a filter program yet"
            o.off -> "Filter: off (B00 on ${SidRules.CHANNEL_NAMES[o.voice]}, order ${hex2(o.order)} row ${hex2(o.row)})"
            else -> "Filter: ${SidRules.CHANNEL_NAMES[o.voice]} runs sound ${hex2(o.slot)} ${song.sid.instruments[o.slot - 1].name}'s " +
                "program (since order ${hex2(o.order)} row ${hex2(o.row)})"
        }
        val state = SharedState(tags, listOf(line))
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }
}
