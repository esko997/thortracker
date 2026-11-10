package org.thortracker.system.tia

import org.thortracker.backend.TiaPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SharedState

/**
 * The registers a note keys on with: [note] with sound [slot] on waveform [waveform] (AUDC, after the waveform
 * envelope's first step or `Wxx`), the divider [audf] it writes (null: a constant waveform in tonal mode writes none),
 * that divider's frequency [hz] (null for the constant waveforms) and, for tonal sounds, the error against the
 * requested pitch in [cents]. [glide]: the note glided there (`3xx`) without a key-on; the registers are its target's.
 */
data class TiaKeyOn(
    val note: Int, val slot: Int, val waveform: Int, val raw: Boolean, val audf: Int?, val hz: Double?, val cents: Double?,
    val glide: Boolean,
)

/**
 * A straight-play walk of an Atari 2600 song from row data alone, as the player runs rows: orders in sequence (jumps
 * and breaks not followed), rows, within a row `Fxx` first (the last wins), then each channel at tick 0 or at its
 * `Gxx` tick (never when it is not below the speed), its `Sxx` OFF at its tick. Per channel it carries the sound
 * (instrument column), the `Wxx` waveform override (cleared by a note row without `W`), `Pxx`, `3xx` and whether a
 * note sounds (OFF stops it unless the sound's volume envelope has a release point). Envelopes, slides, vibrato and
 * volume are not run: [onKeyOn] receives the **key-on registers** of each note (envelopes at their first step).
 */
internal class TiaWalk(private val song: Song) {
    private val sound = song.tia
    private val region = sound.region
    var speed = song.ticksPerRow
        private set
    private val slot = IntArray(CHANNELS)
    private val latched = IntArray(CHANNELS)
    private val override = IntArray(CHANNELS) { -1 }
    private val fine = IntArray(CHANNELS)
    private val porta = IntArray(CHANNELS)
    private val sounding = BooleanArray(CHANNELS)

    /** The last key-on (or glide) of each channel while it sounds; null when silent. */
    val keyOn = arrayOfNulls<TiaKeyOn>(CHANNELS)

    /** The sound the channel's next note keys on with (the last instrument column), 0 = none yet. */
    fun carried(channel: Int): Int = slot[channel]

    /**
     * Runs row [row] of [pattern] (an index into the song's patterns): [onKeyOn] hears each key-on or glide, [onRow]
     * each channel's row as it runs (after its instrument byte).
     */
    fun row(pattern: Int, row: Int, onKeyOn: (Int, TiaKeyOn) -> Unit = { _, _ -> }, onRow: (Int, Cell) -> Unit = { _, _ -> }) {
        val p = song.patterns.getOrNull(pattern) ?: return
        if (p.tracks.size != CHANNELS) return
        for (ch in 0 until CHANNELS) {
            val c = p.tracks[ch][row]
            for (col in 0..c.more.size) if (c.effectCode(col) == TiaRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
        }
        for (ch in 0 until CHANNELS) {
            val c = p.tracks[ch][row]
            var delay = 0
            var cut = -1
            for (col in 0..c.more.size) {
                when (c.effectCode(col)) {
                    TiaRules.FX_DELAY -> delay = c.effectParam(col)
                    TiaRules.FX_CUT -> cut = c.effectParam(col)
                }
            }
            if (delay >= speed) continue
            run(ch, c, onKeyOn, onRow)
            if (cut in delay until speed) off(ch)
        }
    }

    private fun run(ch: Int, c: Cell, onKeyOn: (Int, TiaKeyOn) -> Unit, onRow: (Int, Cell) -> Unit) {
        if (c.note >= 0) { override[ch] = -1; fine[ch] = 0 }
        if (c.instrument > 0) slot[ch] = c.instrument
        for (col in 0..c.more.size) {
            val param = c.effectParam(col)
            when (c.effectCode(col)) {
                TiaRules.FX_PORTAMENTO -> porta[ch] = param
                TiaRules.FX_SLIDE_UP, TiaRules.FX_SLIDE_DOWN -> porta[ch] = 0
                TiaRules.FX_FINE -> fine[ch] = param - TiaRules.FINE_CENTRE
                TiaRules.FX_WAVEFORM -> if (param <= TiaPlayerImage.WAVEFORM_MAX) override[ch] = param
            }
        }
        onRow(ch, c)
        when {
            c.note == Cell.CUT -> off(ch)
            c.note >= 0 -> {
                val rawLatched = sound.instrument(latched[ch])?.raw == true
                val glide = porta[ch] > 0 && sounding[ch] && !rawLatched
                val use = if (glide) latched[ch] else slot[ch]
                val ins = sound.instrument(use) ?: return
                latched[ch] = use
                sounding[ch] = true
                val k = keyOnOf(c.note, use, ins, glide, ch)
                keyOn[ch] = k
                onKeyOn(ch, k)
            }
        }
    }

    private fun keyOnOf(note: Int, use: Int, ins: TiaInstrument, glide: Boolean, ch: Int): TiaKeyOn {
        val w = ins.keyOnWaveform(override[ch])
        val semis = ins.arpeggio.values.firstOrNull() ?: 0
        if (ins.raw) {
            val a = TiaRules.rawAudf(note, semis)
            return TiaKeyOn(note, use, w, true, a, TiaRules.frequency(region, w, a), null, glide)
        }
        val detune = fine[ch] + (ins.pitch.values.firstOrNull() ?: 0)
        val played = TiaRules.played(region, w, note, detune, semis)
        return TiaKeyOn(note, use, w, false, played?.audf, played?.hz, played?.cents, glide)
    }

    private fun off(ch: Int) {
        if (!sounding[ch]) return
        if (sound.instrument(latched[ch])?.volume?.release?.let { it >= 0 } == true) return
        sounding[ch] = false
        keyOn[ch] = null
    }

    companion object {
        const val CHANNELS = TiaPlayerImage.CHANNELS
    }
}

/**
 * What the Pattern view shows of the TIA at the cursor: per channel the **key-on registers** of the note straight
 * play last keyed on at or before the row (computed from row data and the carried sound only: [TiaWalk]; envelopes,
 * slides, vibrato and volume are not run), as a lane tag ("C·11": AUDC · AUDF; "0·DC" for a constant waveform) and an
 * inspector line (note, waveform, AUDF, frequency, cents off). Orders before the cursor's that play (a parked order
 * only on its own) are walked first. Cached per position.
 */
object TiaSharedState {
    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /** The key-on registers per channel after row [row] of order [order] has run (all null when [order] does not exist). */
    fun at(song: Song, order: Int, row: Int): List<TiaKeyOn?> {
        val walk = TiaWalk(song)
        if (order !in song.orders.indices) return walk.keyOn.toList()
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns.getOrNull(song.orders[o]) ?: continue
            val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
            for (r in 0..last) walk.row(song.orders[o], r)
        }
        return walk.keyOn.toList()
    }

    /** The lane tag of [k]: "C·11" (AUDC · AUDF, decimal), "8·5", "0·DC"; null when silent. */
    fun tag(k: TiaKeyOn?): String? = k?.let { "${TiaRules.audcText(it.waveform)}·${it.audf?.toString() ?: "DC"}" }

    /** The inspector line of [k] on [channel]. */
    fun line(channel: Int, k: TiaKeyOn?): String {
        val name = TiaRules.CHANNEL_NAMES[channel]
        if (k == null) return "$name: silent"
        val head = "$name: ${if (k.glide) "glides to " else ""}${TiaSystem.noteText(k.note)} · AUDC ${TiaRules.audcText(k.waveform)} " +
            TiaRules.WAVEFORMS[k.waveform].short
        val audf = k.audf ?: return "$head · no AUDF: a DC level"
        val hz = k.hz?.let { " · ${TiaRules.hzText(it)}" } ?: ""
        return when {
            k.raw -> "$head · AUDF $audf (raw)$hz"
            else -> "$head · AUDF $audf$hz · ${TiaRules.centsText(k.cents ?: 0.0)}"
        }
    }

    fun state(song: Song, order: Int, row: Int): SharedState {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val k = at(song, order, row)
        val state = SharedState(k.map { tag(it) }, listOf("Key-on registers (straight play):") + k.indices.map { line(it, k[it]) })
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }
}
