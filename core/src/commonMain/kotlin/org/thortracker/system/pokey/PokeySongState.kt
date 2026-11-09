package org.thortracker.system.pokey

import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SharedState
import org.thortracker.system.SongSetting
import kotlin.math.ceil
import kotlin.math.pow

/** The POKEY rows of the Song view: the machine, multispeed and the shared AUDCTL bits the song starts with. */
object PokeySongSettings {
    private fun with(song: Song, f: (PokeySound) -> PokeySound): Song = f(song.pokey).let { if (it == song.pokey) song else song.copy(sound = it) }

    private fun toggles(f: Int, c: Int) = (f + c) % 2 != 0

    val ALL: List<SongSetting> = listOf(
        SongSetting("Machine", "NTSC or PAL Atari", "Set machine",
            value = { s ->
                val m = s.pokey.machine
                "${m.label}   ${NumberText.fixed(m.clockHz / 1e6, 4)} MHz, ${NumberText.fixed(m.frameHz, 2)} frames per second"
            },
            adjust = { s, f, c ->
                if (!toggles(f, c)) s else with(s) { it.copy(machine = if (it.machine == PokeyMachine.NTSC) PokeyMachine.PAL else PokeyMachine.NTSC) }
            },
            reset = { s -> with(s) { it.copy(machine = PokeyMachine.NTSC) } },
        ),
        SongSetting("Multispeed", "Player ticks per frame", "Set multispeed",
            value = { s ->
                val m = s.pokey.multispeed
                val tps = NumberText.fixed(PokeySystem.ticksPerSecond(s), 1)
                if (m == 1) "1×   $tps ticks per second" else "$m×   $tps ticks per second: speeds and effects count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + c).coerceIn(1, PokeySystem.MAX_MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ),
        SongSetting("Noise", "Noise generator at the start", "Set noise generator",
            value = { s -> if (s.pokey.poly9) "9-bit   buzzy, pitched noise (N01 at the start)" else "17-bit   hiss (N00 at the start)" },
            adjust = { s, f, c -> if (!toggles(f, c)) s else with(s) { it.copy(poly9 = !it.poly9) } },
            reset = { s -> with(s) { it.copy(poly9 = false) } },
        ),
        SongSetting("Base clock", "Base clock at the start", "Set base clock",
            value = { s -> if (s.pokey.base15) "15 kHz   coarse pitches, low notes (K01 at the start)" else "64 kHz   (K00 at the start)" },
            adjust = { s, f, c -> if (!toggles(f, c)) s else with(s) { it.copy(base15 = !it.base15) } },
            reset = { s -> with(s) { it.copy(base15 = false) } },
        ),
    )
}

/**
 * A walk through a POKEY song in straight play (rows in order, jumps and note delays ignored), as the player runs the
 * parts that decide which lane's voice holds which POKEY channel: instruments, notes, OFF and `Sxx` releases,
 * portamento, and the global `F`, `K`, `N`, `W` effects at each row's tick 0 (docs/AUDIO_ENGINE.md, "POKEY: Player").
 * The song check and [PokeyChannelHolders] use it. Rows are fed with [row]; [onCut] hears every channel a key-on takes
 * from another lane's voice, before that voice ends; [onKeyOn] every key-on, [onUnplayable] every note whose sound cannot
 * play on its lane (the player keys nothing on).
 */
internal class PokeyStraightPlay(private val song: Song) {
    private val sound = song.pokey
    private val ticksPerSecond = PokeySystem.ticksPerSecond(song)

    var speed = song.ticksPerRow
        private set
    var base15 = sound.base15
        private set
    var poly9 = sound.poly9
        private set
    var twoTone = 0
        private set

    /** The order and row the last [row] call ran. */
    var currentOrder = 0
        private set
    var currentRow = 0
        private set

    /** The current tick (row starts), counted from the song start. */
    var tick = 0L
        private set

    /** Each lane's instrument (last named slot, 0 = none). */
    val instrument = IntArray(LANES)

    /** The lane whose voice holds POKEY channel c, or -1. */
    val holder = IntArray(LANES) { -1 }

    /** Each lane's voice: its slot (0 = none), what it holds and where it was keyed on. */
    val voiceSlot = IntArray(LANES)
    val voiceNote = IntArray(LANES)
    val voiceOrder = IntArray(LANES)
    val voiceRow = IntArray(LANES)
    private val keyTick = LongArray(LANES)
    private val releaseTick = LongArray(LANES) { -1L }
    private val silencedByRelease = BooleanArray(LANES)
    private val portamento = IntArray(LANES)

    /** The lane's `Vxx` override (-1 none) and `Pxx` fine pitch, as the next key-on's row leaves them. */
    val distortionOverride = IntArray(LANES) { -1 }
    val fine = IntArray(LANES)

    var onCut: ((lane: Int, victim: Int, channel: Int) -> Unit)? = null
    var onKeyOn: ((lane: Int, slot: Int, note: Int) -> Unit)? = null
    var onUnplayable: ((lane: Int, slot: Int, cell: Cell) -> Unit)? = null

    /** Runs row [row] of [pattern] (at [order]). */
    fun row(pattern: Pattern, order: Int, row: Int) {
        currentOrder = order
        currentRow = row
        val lanes = minOf(LANES, pattern.tracks.size)
        for (lane in 0 until lanes) {
            val c = pattern.tracks[lane][row]
            for (col in 0..c.more.size) {
                val p = c.effectParam(col)
                when (c.effectCode(col)) {
                    PokeyRules.FX_SPEED -> if (p > 0) speed = p
                    PokeyRules.FX_CLOCK -> if (p <= 1) base15 = p == 1
                    PokeyRules.FX_POLY -> if (p <= 1) poly9 = p == 1
                    PokeyRules.FX_TWO_TONE -> if (p <= 2) twoTone = p
                }
            }
        }
        for (lane in 0 until lanes) {
            val c = pattern.tracks[lane][row]
            if (c.instrument != 0) instrument[lane] = c.instrument
            if (c.note >= 0) {
                distortionOverride[lane] = -1
                fine[lane] = 0
            }
            var cut = -1
            for (col in 0..c.more.size) {
                val p = c.effectParam(col)
                when (c.effectCode(col)) {
                    PokeyRules.FX_PORTAMENTO -> portamento[lane] = p
                    PokeyRules.FX_DISTORTION -> if (p <= 8) distortionOverride[lane] = p
                    PokeyRules.FX_FINE -> fine[lane] = p - PokeyRules.FINE_CENTRE
                    PokeyRules.FX_CUT -> cut = p
                }
            }
            when {
                c.note == Cell.CUT -> release(lane, tick)
                c.note >= 0 -> {
                    if (portamento[lane] > 0 && held(lane)) {
                        voiceNote[lane] = c.note
                    } else {
                        keyOn(lane, c, order, row)
                    }
                }
            }
            if (cut >= 0 && cut < speed) release(lane, tick + cut)
        }
        tick += speed
    }

    private fun keyOn(lane: Int, cell: Cell, order: Int, row: Int) {
        val slot = instrument[lane]
        val ins = sound.instrument(slot)
        if (ins == null || lane !in ins.kind.lanes || (ins.fast && lane !in ins.kind.fastLanes)) {
            if (slot != 0) onUnplayable?.invoke(lane, slot, cell)
            return
        }
        end(lane)
        val taken = ins.kind.taken(lane)
        for (ch in taken) {
            val v = holder[ch]
            if (v >= 0 && v != lane) {
                onCut?.invoke(lane, v, ch)
                end(v)
            }
        }
        for (ch in taken) holder[ch] = lane
        voiceSlot[lane] = slot
        voiceNote[lane] = cell.note
        voiceOrder[lane] = order
        voiceRow[lane] = row
        keyTick[lane] = tick
        releaseTick[lane] = -1
        silencedByRelease[lane] = false
        onKeyOn?.invoke(lane, slot, cell.note)
    }

    /** The voice of [lane] ends: every channel it holds is free. */
    private fun end(lane: Int) {
        if (voiceSlot[lane] == 0) return
        for (ch in 0 until LANES) if (holder[ch] == lane) holder[ch] = -1
        voiceSlot[lane] = 0
    }

    /** OFF / `Sxx`: envelopes go to their release; a sound without a volume release point, or samples, go silent at once. */
    private fun release(lane: Int, at: Long) {
        val ins = sound.instrument(voiceSlot[lane]) ?: return
        if (releaseTick[lane] >= 0) return
        releaseTick[lane] = at
        if (ins.kind == PokeyKind.SAMPLE || ins.volume.values.isEmpty() || ins.volume.release < 0) silencedByRelease[lane] = true
    }

    /** The voice of [lane] sounds and has not been released (at the current row's start). */
    fun unreleased(lane: Int): Boolean = voiceSlot[lane] != 0 && releaseTick[lane] < 0 && !endedByItself(lane)

    /**
     * The voice of [lane] is held as the player's portamento decides (at the current row's start): keyed on, not
     * released, and for a one-shot sample not yet run to its end (a note then keys on afresh). A synthesized voice
     * whose envelopes have ended is still held: a portamento note only retargets it.
     */
    private fun held(lane: Int): Boolean {
        if (voiceSlot[lane] == 0 || releaseTick[lane] >= 0) return false
        val ins = sound.instrument(voiceSlot[lane]) ?: return false
        return ins.kind != PokeyKind.SAMPLE || !endedByItself(lane)
    }

    /** The voice of [lane] is silent for good: released without a tail, or its one-shot envelope or sample has run out. */
    fun silent(lane: Int): Boolean {
        if (voiceSlot[lane] == 0) return true
        if (silencedByRelease[lane] && releaseTick[lane] <= tick) return true
        val ins = sound.instrument(voiceSlot[lane]) ?: return true
        val r = releaseTick[lane]
        if (r in 0..tick && ins.kind != PokeyKind.SAMPLE) {
            val e = ins.volume
            return e.values.last() == 0 && tick - r >= e.values.size - e.release
        }
        return endedByItself(lane)
    }

    private fun endedByItself(lane: Int): Boolean {
        val ins = sound.instrument(voiceSlot[lane]) ?: return true
        val elapsed = tick - keyTick[lane]
        if (ins.kind == PokeyKind.SAMPLE) {
            if (ins.loop >= 0) return false
            val sample = sound.samples.getOrNull(ins.sample) ?: return true
            val rate = ins.rate * 2.0.pow((voiceNote[lane] - ins.root) / 12.0)
            return elapsed >= ceil(sample.levels.size / rate * ticksPerSecond)
        }
        val e = ins.volume
        if (e.values.isEmpty() || e.loop >= 0 || e.release >= 0) return false
        return e.values.last() == 0 && elapsed >= e.values.size
    }

    companion object {
        const val LANES = 4

        /**
         * Walks [song] in straight play up to and including row [row] of order [order]: the orders that play before it
         * (a parked order plays only on its own), then that order's rows.
         */
        fun upTo(song: Song, order: Int, row: Int): PokeyStraightPlay {
            val walk = PokeyStraightPlay(song)
            val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
            for (o in before + order) {
                val p = song.patterns[song.orders[o]]
                val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
                for (r in 0..last) walk.row(p, o, r)
            }
            return walk
        }
    }
}

/**
 * Which lane's voice holds which POKEY channel at a song position, as straight play reaches it, for the Pattern lane
 * headers and the inspector: "16-bit 1+2" on a 16-bit voice's lane, "held by 2" on the channel it takes, "HP clock of 1"
 * on a high-pass sound's clock channel; plus the shared AUDCTL bits and two-tone mode. Loop passes start the loop order
 * with the state straight play starts it with (the player resets them on arrival), so one walk serves every pass.
 */
object PokeyChannelHolders {
    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    fun state(song: Song, order: Int, row: Int): SharedState? {
        if (order !in song.orders.indices) return null
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val walk = PokeyStraightPlay.upTo(song, order, row)
        val sound = song.pokey
        val names = PokeyRules.CHANNEL_NAMES
        val tags = List(PokeyStraightPlay.LANES) { lane -> tag(walk, sound, lane) }
        val lines = ArrayList<String>()
        lines += "AUDCTL: ${if (walk.base15) "15 kHz" else "64 kHz"} base clock, ${if (walk.poly9) "9-bit" else "17-bit"} noise; " +
            when (walk.twoTone) { 1 -> "two-tone on (W01)"; 2 -> "two-tone sync (W02)"; else -> "two-tone off" }
        for (lane in 0 until PokeyStraightPlay.LANES) {
            val slot = walk.voiceSlot[lane]
            if (slot == 0) continue
            val ins = sound.instrument(slot) ?: continue
            val held = (0 until PokeyStraightPlay.LANES).filter { walk.holder[it] == lane }
            val others = held.filter { it != lane }
            val takes = if (others.isEmpty()) "" else ", takes ${others.joinToString(" and ") { names[it] }}"
            val state = if (walk.silent(lane)) "silent" else if (walk.unreleased(lane)) "sounding" else "released"
            lines += "${names[lane]}: sound ${hex2(slot)} ${ins.name.ifEmpty { "(unnamed)" }}, ${ins.kind.label}$takes; $state " +
                "(since order ${hex2(walk.voiceOrder[lane])} row ${hex2(walk.voiceRow[lane])})"
        }
        val state = SharedState(tags, lines)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }

    private fun tag(walk: PokeyStraightPlay, sound: PokeySound, lane: Int): String? {
        val h = walk.holder[lane]
        if (h < 0) return null
        val ins = sound.instrument(walk.voiceSlot[h]) ?: return null
        val kind = ins.kind
        if (h != lane) return if (kind.filtered && lane >= 2) "HP clock of ${h + 1}" else "held by ${h + 1}"
        val n = lane + 1
        return when (kind) {
            PokeyKind.TONE -> if (ins.fast && lane in kind.fastLanes) "1.79 MHz" else null
            PokeyKind.JOINED -> "16-bit ${n - 1}+$n"
            PokeyKind.FILTERED -> if (ins.difference) "saw $n+${n + 2}" else "HP $n by ${n + 2}"
            PokeyKind.JOINED_FILTERED -> if (ins.difference) "16-bit saw" else "16-bit HP"
            PokeyKind.SAMPLE -> "samples"
        }
    }
}
