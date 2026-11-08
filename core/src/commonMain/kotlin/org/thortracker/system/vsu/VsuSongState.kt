package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SharedState

/**
 * Walks a song row by row as the player runs it in straight play (orders in sequence, jumps ignored; a row delayed
 * past the speed never runs): per lane the sound named last, whether a note is keyed, the shared wave it plays, Wave 5's
 * sweep/modulation byte and the noise tap, after the locks and their at-once writes (docs/AUDIO_ENGINE.md, "Running a
 * row"). Hardware stops (auto length, sweep overflow) are not tracked, as the player does not track them. [warn]
 * receives the honest notes about rows whose effects do nothing or stop the channel (each pattern row once).
 */
internal class VsuWalk(private val song: Song, private val warn: ((String) -> Unit)? = null) {
    val instrument = IntArray(LANES)
    val keyed = BooleanArray(LANES)
    val keyedSlot = IntArray(LANES)
    val keyOrder = IntArray(LANES) { -1 }
    val keyRow = IntArray(LANES) { -1 }
    val wave = IntArray(LANES)
    val swp = IntArray(LANES)
    val modulation = BooleanArray(LANES)
    val freqModOn = BooleanArray(LANES)
    val tap = IntArray(LANES)
    private val porta = IntArray(LANES)
    var speed = song.ticksPerRow
        private set

    private val warned = HashSet<String>()

    private fun note(key: String, message: () -> String) {
        val w = warn ?: return
        if (warned.add(key)) w(message())
    }

    /** Runs row [row] of [pattern] (pattern index [p]) played at order [order]. */
    fun row(order: Int, p: Int, pattern: Pattern, row: Int) {
        for (lane in 0 until LANES) {
            val c = pattern.tracks[lane][row]
            for (col in 0..c.more.size) if (c.effectCode(col) == VsuRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
        }
        for (lane in 0 until LANES) lane(order, p, pattern, row, lane)
    }

    private fun lane(order: Int, p: Int, pattern: Pattern, row: Int, lane: Int) {
        val c = pattern.tracks[lane][row]
        fun where() = "${Songs.patternLabel(song, p).replaceFirstChar { it.uppercase() }}, ${VsuRules.CHANNEL_NAMES[lane]} row $row"
        var delay = 0
        for (col in 0..c.more.size) if (c.effectCode(col) == VsuRules.FX_DELAY && c.effectParam(col) > 0) delay = c.effectParam(col)
        if (delay > 0 && delay >= speed) {
            note("$p/$lane/$row/G") { "${where()}: G${hex2(delay)} waits $delay ticks but rows are $speed ticks; the row never plays" }
            return
        }
        var level = -1
        var length = -1
        var m = -1
        var n = -1
        var w = -1
        var fine = 0
        var cut = -1
        var lock: String? = null
        for (col in 0..c.more.size) {
            val code = c.effectCode(col)
            val param = c.effectParam(col)
            if (code == 0 && param == 0) continue
            when (code) {
                VsuRules.FX_LEVEL -> level = param
                VsuRules.FX_LENGTH -> length = param
                VsuRules.FX_SWEEP_MOD -> m = param
                VsuRules.FX_NOISE_TAP -> n = param
                VsuRules.FX_WAVE -> w = param
                VsuRules.FX_FINE -> fine = param - VsuRules.FINE_CENTRE
                VsuRules.FX_PORTAMENTO -> porta[lane] = param
                VsuRules.FX_CUT -> cut = param
            }
            if (code in VsuRules.LOCKS && code != VsuRules.FX_LENGTH && lock == null) lock = VsuRules.effectText(code, param)
        }
        if (c.instrument != 0) instrument[lane] = c.instrument
        val slot = instrument[lane]
        val ins = song.vsu.instrument(slot)
        val lengthText = if (length >= 0) VsuRules.effectText(VsuRules.FX_LENGTH, length) else null
        // A row keys on only with a note (not OFF), a defined instrument and no glide; every other row writes its C/N/W/M
        // locks to the sounding channel at once (before an OFF's key-off) and its L does nothing.
        val glide = c.note >= 0 && porta[lane] > 0 && keyed[lane]
        if (c.note >= 0 && !glide && ins != null) {
            keyOn(order, p, row, lane, c.note, slot, ins, level, m, n, w, fine, ::where)
        } else {
            if (lengthText != null) {
                val why = when {
                    glide -> "this note glides (3xx) without starting"
                    c.note >= 0 -> "this note's instrument is empty, so it does not start"
                    else -> "this row starts no note"
                }
                note("$p/$lane/$row/L") { "${where()}: $lengthText applies when a note starts; $why, so it does nothing" }
            }
            if (lock != null) when {
                !keyed[lane] -> note("$p/$lane/$row/idle") { "${where()}: $lock does nothing: no note is sounding on this lane here" }
                c.note == Cell.CUT -> note("$p/$lane/$row/off") { "${where()}: $lock is not heard: the row's OFF stops the channel right after it" }
                else -> {
                    if (w in 1..VsuPlayerImage.WAVES) wave[lane] = w - 1
                    if (m >= 0) swp[lane] = m
                    if (n >= 0) tap[lane] = n
                }
            }
            if (c.note == Cell.CUT) keyed[lane] = false
        }
        if (cut in 0 until speed) keyed[lane] = false
    }

    private fun keyOn(order: Int, p: Int, row: Int, lane: Int, n0: Int, slot: Int, ins: VsuInstrument, level: Int, m: Int, n: Int, w: Int, fine: Int, where: () -> String) {
        keyed[lane] = true
        keyedSlot[lane] = slot
        keyOrder[lane] = order
        keyRow[lane] = row
        wave[lane] = if (w in 1..VsuPlayerImage.WAVES) w - 1 else ins.wave
        tap[lane] = if (n >= 0) n else ins.noiseTap
        swp[lane] = if (m >= 0) m else ins.freqMod.swpByte
        modulation[lane] = ins.freqMod.modulation
        freqModOn[lane] = ins.freqMod.on
        val name = ins.name.ifEmpty { "sound ${hex2(slot)}" }
        if (lane != VsuRules.SWEEP_LANE && ins.freqMod.on) {
            note("fm/$slot/$lane") { "Sound ${hex2(slot)} \"$name\" uses sweep/modulation, which only Wave 5 has; on ${VsuRules.CHANNEL_NAMES[lane]} it is ignored" }
        }
        if (lane == VsuRules.SWEEP_LANE) {
            val up = swp[lane] and VsuPlayerImage.SWP_UP != 0
            val shift = swp[lane] and VsuPlayerImage.SWP_SHIFT_MASK
            if (!ins.freqMod.modulation && up) {
                val pitch = 64 * n0 + fine + if (ins.sweep.active) 64 * ins.sweep.start else 0
                val f = VsuPitch.register(lane, pitch)
                if (VsuPitch.sweepStops(f, shift)) {
                    val running = ins.freqMod.on && (swp[lane] shr VsuPlayerImage.SWP_INTERVAL_SHIFT) and 7 != 0
                    note("$p/$lane/$row/overflow") {
                        "${where()}: ${VsuSystem.noteName(n0)} (F $f) with sweep up, shift $shift stops Wave 5 at once: F + F>>$shift passes 2047" +
                            (if (running) "" else ", which the hardware checks even with the sweep off") + ". Sweep down, a larger shift or a lower note plays"
                    }
                }
            }
            if (ins.freqMod.on && ins.freqMod.modulation && song.vsu.modulation.all { it == 0 }) {
                note("mod0") { "Sound ${hex2(slot)} \"$name\" modulates Wave 5, but the modulation table is all 0: it changes nothing (Wave view, last page)" }
            }
        }
        val start = if (level >= 0) level else ins.level
        if (start == 0 && !(ins.envelope && ins.grow)) {
            note("silent/$slot/$level") { "${where()}: sound ${hex2(slot)} \"$name\" starts at level 0 without a growing envelope: the note is silent" }
        }
    }

    companion object {
        const val LANES = VsuPlayerImage.CHANNELS

        /** The document orders straight play runs before reaching [order]: none for a parked order (it plays only alone). */
        fun ordersBefore(song: Song, order: Int): List<Int> =
            if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }

        /** The state after straight play has run ([order], [row]) of the document [song], that row included. */
        fun upTo(song: Song, order: Int, row: Int): VsuWalk {
            val walk = VsuWalk(song)
            for (o in ordersBefore(song, order)) {
                val p = song.orders[o]
                val pattern = song.patterns[p]
                for (r in 0 until pattern.length) walk.row(o, p, pattern, r)
            }
            val p = song.orders[order]
            val pattern = song.patterns[p]
            for (r in 0..row.coerceIn(0, pattern.length - 1)) walk.row(order, p, pattern, r)
            return walk
        }
    }
}

/** Which shared wave each lane plays at a position, and Wave 5's sweep/modulation and the noise tap ([VsuSystem.sharedState]). */
internal object VsuLaneState {
    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    fun state(song: Song, order: Int, row: Int): SharedState? {
        if (order !in song.orders.indices) return null
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val walk = VsuWalk.upTo(song, order, row)
        val sound = song.vsu
        val tags = List(VsuWalk.LANES) { lane -> tag(walk, lane) }
        val lines = ArrayList<String>()
        for (lane in 0 until VsuWalk.LANES) {
            val name = VsuRules.CHANNEL_NAMES[lane]
            if (!walk.keyed[lane]) { lines += "$name: silent"; continue }
            val slot = walk.keyedSlot[lane]
            val ins = sound.instrument(slot)
            val since = "since order ${hex2(walk.keyOrder[lane])} row ${hex2(walk.keyRow[lane])}"
            val who = "sound ${hex2(slot)} ${ins?.name?.ifEmpty { "(unnamed)" } ?: "(empty)"}"
            lines += when (lane) {
                VsuRules.NOISE_LANE -> "$name: $who, tap ${walk.tap[lane]} (${VsuPitch.tapLength(walk.tap[lane])}-step sequence), $since"
                VsuRules.SWEEP_LANE -> "$name: $who, wave ${walk.wave[lane] + 1}${waveName(sound, walk.wave[lane])}, ${freqModText(walk, lane)}, $since"
                else -> "$name: $who, wave ${walk.wave[lane] + 1}${waveName(sound, walk.wave[lane])}, $since"
            }
        }
        val users = (0 until VsuPlayerImage.WAVES).map { w -> (0 until VsuRules.NOISE_LANE).filter { walk.keyed[it] && walk.wave[it] == w } }
        if (users.any { it.size > 1 }) {
            lines += "Shared: " + users.withIndex().filter { it.value.size > 1 }.joinToString("; ") { (w, lanes) ->
                "wave ${w + 1} on ${lanes.joinToString(", ") { VsuRules.CHANNEL_NAMES[it] }}"
            }
        }
        val state = SharedState(tags, lines)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }

    /** " (Sine)" when wave [w] of [sound] is one of the preset shapes, else "". */
    private fun waveName(sound: VsuSound, w: Int): String = VsuWaves.name(sound, w)?.let { " ($it)" } ?: ""

    private fun tag(walk: VsuWalk, lane: Int): String? {
        if (!walk.keyed[lane]) return null
        return when (lane) {
            VsuRules.NOISE_LANE -> "tap ${walk.tap[lane]}"
            VsuRules.SWEEP_LANE -> "W${walk.wave[lane] + 1}" + when {
                !walk.freqModOn[lane] || (walk.swp[lane] shr VsuPlayerImage.SWP_INTERVAL_SHIFT) and 7 == 0 -> ""
                walk.modulation[lane] -> " mod"
                walk.swp[lane] and VsuPlayerImage.SWP_UP != 0 -> " sweep↑"
                else -> " sweep↓"
            }
            else -> "W${walk.wave[lane] + 1}"
        }
    }

    private fun freqModText(walk: VsuWalk, lane: Int): String {
        val swp = walk.swp[lane]
        return when {
            !walk.freqModOn[lane] -> "sweep/modulation off"
            walk.modulation[lane] -> "modulation ${VsuRules.swpText(swp).substringBefore(", sweep")}"
            else -> "sweep ${VsuRules.swpText(swp)}"
        }
    }
}
