package org.thortracker.system.sid

import org.thortracker.backend.GtSong
import org.thortracker.backend.SidLowering
import org.thortracker.backend.SidPlayerImage
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * C64 SID checks, layered like the other systems':
 * - [structuralErrors]: shape and value ranges every consumer relies on; such projects are neither saved nor loaded.
 * - [errors]: plus everything GoatTracker's playroutine cannot play as written — effect parameters, program rows,
 *   table rows (255 per table), gate timers against the tempo, GoatTracker's limits on patterns (208 distinct voice
 *   patterns, 256 packed bytes each) and the song data region (`$2500-$CFFF`), mirroring the engine's image checks
 *   (`sm_load_image`).
 * [warnings] are legal but probably unintended. Messages name the location and the fix.
 */
object SidValidator {
    const val MAX_REPORTED = 100

    /** PSID title/author/released fields: 32 Latin-1 characters (zero-padded when shorter). */
    const val PSID_TEXT = 32

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is SidSound) {
            out += "The song is for ${song.system.name}, not the C64 SID"
            return out.list()
        }
        val channels = SidRules.CHANNEL_NAMES.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; SID patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${SidRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    SidRules.cellRangeProblem(cell)?.let { out += "$label, ${SidRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val sid = song.sid
        if (sid.instruments.size != SidSystem.SLOTS) out += "The song has ${sid.instruments.size} instruments; SID songs have exactly ${SidSystem.SLOTS}"
        if (sid.multispeed !in SidSound.MULTISPEED) out += "Multispeed ${sid.multispeed} is outside 1-16"
        if (sid.hardRestartAttackDecay !in 0..0xFF || sid.hardRestartSustainRelease !in 0..0xFF) {
            out += "The hard restart envelope must be two bytes 00-FF"
        }
        sid.instruments.forEachIndexed { i, ins ->
            val where = "Instrument ${SidRules.hex2(i + 1)}"
            for ((name, v) in listOf("attack" to ins.attack, "decay" to ins.decay, "sustain" to ins.sustain, "release" to ins.release)) {
                if (v !in SidInstrument.NIBBLE) out += "$where: $name $v is outside 0-15"
            }
            if (ins.gateTimer !in SidInstrument.GATE_TIMER) out += "$where: gate timer ${ins.gateTimer} is outside 1-63 ticks"
            if (ins.firstWave !in 0..0xFF) out += "$where: first wave ${ins.firstWave} is outside 00-FF"
            val v = ins.vibrato
            if (v.delay !in SidVibrato.DELAY) out += "$where: vibrato delay ${v.delay} is outside 0-255"
            if (v.active && v.speed !in SidVibrato.SPEED) out += "$where: vibrato speed ${v.speed} is outside 1-127"
            if (v.depth !in SidVibrato.DEPTH) out += "$where: vibrato depth ${v.depth} is outside 0-255"
            for (t in SidTables.WAVE..SidTables.FILTER) {
                val p = ins.program(t)
                if (p.rows.size > SidTables.ROWS) out += "$where: the ${SidTables.NAMES[t].lowercase()} program has ${p.rows.size} rows; tables have ${SidTables.ROWS}"
                if (p.at !in 0..SidTables.ROWS) out += "$where: the ${SidTables.NAMES[t].lowercase()} program's row ${p.at} is outside 0-255"
                p.rows.firstOrNull { it.left !in 0..0xFF || it.right !in 0..0xFF }?.let {
                    out += "$where: ${SidTables.NAMES[t].lowercase()} program row bytes must be 00-FF"
                }
            }
        }
        song.tuning?.problems(SidSystem)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = SidSystem.arranged(document)
        val out = Report()
        if (song.orders.size > SidSystem.maxOrders) out += "The song has ${song.orders.size} orders; GoatTracker plays at most ${SidSystem.maxOrders}"
        if (song.ticksPerRow !in SidRules.TEMPOS) {
            out += "Song tempo ${song.ticksPerRow} ticks per row is outside GoatTracker's ${SidRules.TEMPOS.first}-${SidRules.TEMPOS.last}"
        }
        val sid = song.sid
        sid.instruments.forEachIndexed { i, ins ->
            val where = "Instrument ${SidRules.hex2(i + 1)} \"${ins.name}\""
            if (ins.firstWave in 0x01..0x07) {
                out += "$where: first wave ${SidRules.hex2(ins.firstWave)} has no waveform; use 09 (test + gate), 00 (keep), FE/FF (gate off/on) or a waveform"
            }
            for (t in SidTables.WAVE..SidTables.FILTER) {
                val rows = ins.program(t).rows
                for (r in rows.indices) SidTables.rowProblem(t, rows, r)?.let {
                    out += "$where, ${SidTables.NAMES[t].lowercase()} program row ${r + 1}: $it"
                }
                if (t != SidTables.WAVE) continue
                for (r in rows) {
                    val c = r.left - SidTables.WAVE_COMMAND
                    val table = SidRules.programTable(c)
                    if (r.left in SidTables.WAVE_COMMAND until SidTables.JUMP && table >= 0 && r.right in 1..SidSystem.SLOTS &&
                        sid.instruments[r.right - 1].program(table).rows.isEmpty()
                    ) out += "$where, wave program: ${SidRules.effectText(c, r.right)} names instrument ${SidRules.hex2(r.right)}, which has no ${SidTables.NAMES[table].lowercase()} program"
                }
            }
        }
        for (t in SidTables.WAVE..SidTables.FILTER) {
            val placed = SidTables.placement(sid, t)
            if (placed.overflow > 0) {
                out += "${SidTables.NAMES[t]} programs need ${placed.used + placed.overflow} table rows; GoatTracker's ${SidTables.NAMES[t].lowercase()} " +
                    "table has ${SidTables.ROWS}. Shorten programs or let instruments share identical ones"
            }
        }
        val speeds = SidSpeeds.of(song)
        if (speeds.size > SidTables.ROWS) {
            out += "Vibratos, portamentos and funktempos use ${speeds.size} different speeds; GoatTracker's speedtable has ${SidTables.ROWS} rows. " +
                "Reuse values"
        }
        val played = song.orders.toSet()
        song.patterns.forEachIndexed { p, pattern ->
            if (p !in played) return@forEachIndexed
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    SidRules.cellProblem(song, track[row])?.let { out += "$label, ${SidRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        gateTimers(song).forEach { out += it }
        if (out.isEmpty()) {
            try {
                SidLowering.lower(GtSong.of(song), ByteArray(SidPlayerImage.IMAGE_SIZE))
            } catch (e: IllegalArgumentException) {
                out += e.message ?: "The song cannot be lowered for the C64 player"
            }
        }
        return out.list()
    }

    /**
     * A note without instrument before any instrument has played on its voice: the player then plays its instrument
     * number 1 (the first hard-restart instrument by slot), which is rarely what was meant.
     */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val out = ArrayList<String>()
        for (ch in SidRules.CHANNEL_NAMES.indices) {
            loop@ for (p in song.orders) {
                val pattern = song.patterns[p]
                for (row in 0 until pattern.length) {
                    val cell = pattern.tracks[ch][row]
                    if (cell.instrument != 0) break@loop
                    if (cell.note < 0) continue
                    out += "${Songs.patternLabel(song, p).cap()}, ${SidRules.CHANNEL_NAMES[ch]} row $row: ${SidSystem.noteName(cell.note)} has no " +
                        "instrument and none has played on this voice yet, so the player's first instrument sounds; enter the instrument number"
                    break@loop
                }
            }
        }
        return out
    }

    /** The shortest row the song plays, in ticks (start tempo, `Fxx`, both `Exy` tempos). */
    fun shortestRow(song: Song): Int {
        var min = song.ticksPerRow
        val played = song.orders.toSet()
        for (p in played) {
            val pattern = song.patterns[p]
            for (track in pattern.tracks) for (row in 0 until pattern.length) {
                val c = track[row]
                when {
                    c.effect == SidRules.FX_TEMPO && c.param in SidRules.TEMPOS -> min = minOf(min, c.param)
                    c.effect == SidRules.FX_FUNKTEMPO -> min = minOf(min, c.param shr 4, c.param and 0x0F)
                }
            }
        }
        return min
    }

    /** GoatTracker fetches a row gate-timer ticks before it starts, so gate timers must stay below the shortest row. */
    private fun gateTimers(song: Song): List<String> {
        val shortest = shortestRow(song)
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (c in track) if (c.instrument > 0) used += c.instrument
        return used.sorted().mapNotNull { slot ->
            val ins = song.sid.instruments[slot - 1]
            if (ins.gateTimer < shortest) null
            else "Instrument ${SidRules.hex2(slot)} \"${ins.name}\": gate timer ${ins.gateTimer} is not below the shortest row " +
                "($shortest ticks); GoatTracker fetches each row gate-timer ticks early, so lower it or slow the tempo"
        }
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = SidSystem.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        if (song.title.length > PSID_TEXT || song.title.any { it.code !in 0x20..0xFF }) {
            out += "The .sid title field holds $PSID_TEXT Latin-1 characters; the title will be shortened or changed there"
        }
        if (song.artist.length > PSID_TEXT || song.artist.any { it.code !in 0x20..0xFF }) {
            out += "The .sid author field holds $PSID_TEXT Latin-1 characters; the artist will be shortened or changed there"
        }
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (c in track) if (c.instrument > 0) used += c.instrument
        for (slot in used.sorted()) {
            val ins = song.sid.instruments[slot - 1]
            if (ins.attackDecay == 0 && ins.sustainRelease == 0) {
                out += "Instrument ${SidRules.hex2(slot)} \"${ins.name}\" has attack, decay, sustain and release 0: only a click sounds"
            }
        }
        return out.list()
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        add("Instruments" to "${used.size} of ${SidSystem.SLOTS} used")
        for (t in SidTables.WAVE..SidTables.FILTER) {
            val placed = SidTables.placement(song.sid, t)
            add("${SidTables.NAMES[t]} table" to "${placed.used + placed.overflow} of ${SidTables.ROWS} rows")
        }
        add("Speed table" to "${SidSpeeds.of(song).size} of ${SidTables.ROWS} rows")
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private class Report {
        private val items = ArrayList<String>()
        private var dropped = 0
        operator fun plusAssign(message: String) {
            if (items.size < MAX_REPORTED) items += message else dropped++
        }
        fun isEmpty() = items.isEmpty()
        fun list(): List<String> = if (dropped == 0) items else items + "...and $dropped more"
    }
}
