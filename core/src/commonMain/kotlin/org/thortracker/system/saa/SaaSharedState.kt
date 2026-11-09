package org.thortracker.system.saa

import org.thortracker.backend.SaaPlayerImage
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SharedState

/**
 * The chips' shared resources where straight play reaches a song position ([SaaWalk], document orders: those that play
 * before it, a parked order only on its own, rows up to and including the position's), for the Pattern lane headers
 * and the inspector:
 * - each noise generator's clock on the lane that claimed it last ("N0:31k": generator 0 of lanes 1-3, "N1:T4":
 *   generator 1 at twice lane 4's tone), with a line naming the claim and any lane of the triple sounding noise at
 *   another clock of its own;
 * - a sounding hardware envelope on lane 3 or 6: "E←2" / "E←5" (clocked by lane 2's / 5's tone generator) or "E ext",
 *   with a line saying what it plays (for a repeating internal one, how far below its clock lane's note it sounds);
 * - "SYNC" / "OFF" on every lane of a chip that `Y02` / `Y03` holds.
 */
object SaaSharedState {
    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /** The walk after row [row] of order [order] has run (the song start when [order] does not exist). */
    internal fun walk(song: Song, order: Int, row: Int): SaaWalk {
        val sound = song.saa
        val walk = SaaWalk(sound, song.ticksPerRow)
        if (order !in song.orders.indices) return walk
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns.getOrNull(song.orders[o]) ?: continue
            if (p.tracks.size != sound.variant.channels || p.tracks.any { it.size != Songs.ROWS }) continue
            val length = p.length.coerceIn(1, Songs.ROWS)
            val last = if (o == order) minOf(row, length - 1) else length - 1
            for (r in 0..last) walk.row(p.tracks, o, r)
        }
        return walk
    }

    /** The clock noise generator [generator] (0: lanes 1-3, 1: lanes 4-6) of [chip] holds after row [row] of order [order], or -1 before any claim. */
    fun noiseClock(song: Song, order: Int, row: Int, chip: Int = 0, generator: Int): Int {
        val w = walk(song, order, row)
        val gi = 2 * chip + generator
        return if (w.owner[gi] == null) -1 else w.generator[gi]
    }

    /** The lane tags and inspector lines. Cached per position. */
    fun state(song: Song, order: Int, row: Int): SharedState? {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) return cacheState
        val state = compute(song, order, row)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }

    private fun compute(song: Song, order: Int, row: Int): SharedState? {
        val sound = song.sound as? SaaSound ?: return null
        val variant = sound.variant
        val names = variant.laneNames
        val w = walk(song, order, row)
        val tags = arrayOfNulls<String>(variant.channels)
        val lines = ArrayList<String>()
        fun tag(ch: Int, text: String) { tags[ch] = tags[ch]?.let { "$it $text" } ?: text }
        for (chip in 0 until variant.chips) {
            val chipText = if (variant.chips == 1) "" else "Chip ${'A' + chip}, "
            for (g in 0..1) {
                val gi = 2 * chip + g
                val lanes = SaaSound.generatorLanes(chip, g)
                val range = "${names[lanes.first]}-${names[lanes.last]}"
                val claim = w.owner[gi] ?: continue
                val clock = w.generator[gi]
                tag(claim.channel, "N$g:" + SaaRules.noiseTag(clock, variant, g))
                val ins = sound.instrument(claim.slot)
                lines += "${chipText}noise generator $g ($range): ${SaaRules.noiseText(clock, variant, claim.channel)}, set by " +
                    "${names[claim.channel]}'s note (instrument ${NumberText.hex(claim.slot, 2)} \"${ins?.name.orEmpty()}\") at order " +
                    "${NumberText.hex(claim.order, 2)} row ${claim.row}; every lane of $range that plays noise plays this clock"
                for (ch in lanes) {
                    val own = w.soundingNoise[ch]
                    if (ch == claim.channel || own < 0 || own == clock) continue
                    lines += "${names[ch]} sounds noise too: at this clock, not its own ${SaaRules.noiseText(own, variant, ch)}"
                }
            }
            for (lane in listOf(2, 5)) {
                val ch = chip * SaaSound.CHIP_CHANNELS + lane
                val v = w.control(ch)
                if (v == 0) continue
                val external = v and SaaPlayerImage.ENV_EXTERNAL != 0
                val clockLane = SaaSound.envelopeClockLane(ch)
                tag(ch, if (external) "E ext" else "E←${lane}")
                val text = SaaRules.envelopeText(v, variant, ch, if (external) w.rate(ch) else 0)
                val e = SaaHardwareEnvelope.ofControl(v)
                val pitch = when {
                    external -> ""
                    !w.active[clockLane] -> "; ${names[clockLane]} has not played yet, so its tone generator sits at the chip's lowest tone"
                    else -> e?.let(SaaRules::envelopeInterval)?.let { interval ->
                        val n = w.note[clockLane]
                        val below = n - interval
                        "; ${names[clockLane]} plays ${variant.system.noteName(n)}, so the envelope repeats " +
                            "${interval / 12} octaves below" + if (below >= 0) " (${variant.system.noteName(below)})" else ""
                    } ?: "; it steps twice per period of ${names[clockLane]}'s tone"
                }
                lines += "${chipText}hardware envelope ${lane / 3} (${names[ch]}): $text$pitch"
            }
            val sync = w.sync[chip]
            if (sync != null && (sync.value == SaaRules.SYNC_HOLD || sync.value == SaaRules.SYNC_OFF)) {
                val label = if (sync.value == SaaRules.SYNC_HOLD) "SYNC" else "OFF"
                for (ch in chip * SaaSound.CHIP_CHANNELS until (chip + 1) * SaaSound.CHIP_CHANNELS) tags[ch] = label
                lines += "$chipText${SaaRules.effectText(SaaRules.FX_SYNC, sync.value)} on ${names[sync.channel]} at order " +
                    "${NumberText.hex(sync.order, 2)} row ${sync.row}: ${SaaRules.syncText(sync.value)}"
            }
        }
        if (lines.isEmpty()) lines += "No lane has started noise or a hardware envelope yet"
        return SharedState(tags.toList(), lines.map { it.replaceFirstChar { c -> c.uppercase() } })
    }
}
