package org.thortracker.system.nes

import org.thortracker.backend.FamiStudioEnvelopes
import org.thortracker.backend.FamiStudioEnvelopes.Kind
import org.thortracker.backend.FsNote
import org.thortracker.backend.FsSong
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SharedState
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * The Sunsoft 5B's shared hardware, as FamiStudio's sound engine 4.5.3 drives it (`famistudio_update_s5b_channel_sound`):
 * three squares with one envelope generator and one noise generator between them.
 * - Envelope: every frame the highest-numbered square that sounds (its note not cut) a sound with a [S5bEnvelope.shape]
 *   sets the generator's shape and period: auto (its note's period shifted by its octave) or manual (the period the last
 *   loaded manual sound or `Ixx`/`Jyy` left in the engine). Any such square's note with attack restarts the generator.
 * - Noise: every frame the highest-numbered sounding square sets the noise period from its noise envelope; Square 1 only
 *   when its mixer lets noise through, Squares 2 and 3 always (the engine tests their mixer value against masks meant for
 *   shifted values, so the test never skips them; FamiStudio's own editor preview differs, its exported engine plays this).
 * [warnings] point out where squares fight over them; [state] says who owns them at a song position for the Pattern view.
 */
object NesS5b {
    /** The NES CPU clock that drives the 5B (NTSC). */
    const val CPU_HZ = 1789773.0

    /** Seconds one envelope step lasts at [period] (16 CPU cycles per count; period 0 acts as 1). */
    fun stepSeconds(period: Int): Double = 16.0 * maxOf(1, period) / CPU_HZ

    /** Seconds one ramp (32 steps) lasts at [period]. */
    fun rampSeconds(period: Int): Double = 32 * stepSeconds(period)

    /** Repeating shapes: cycles per second at [period] (a triangle takes two ramps). */
    fun cycleHz(shape: S5bShape, period: Int): Double = 1.0 / (rampSeconds(period) * if (shape.triangle) 2 else 1)

    /** "286 ms", "4.6 s", "71 µs": a duration. */
    fun duration(seconds: Double): String = when {
        seconds >= 1.0 -> "${NumberText.fixed(seconds, 1)} s"
        seconds >= 0.001 -> "${(seconds * 1000).roundToInt()} ms"
        else -> "${(seconds * 1e6).roundToInt()} µs"
    }

    /** "3.5 Hz", or for audible rates "437 Hz ≈ A-4". */
    fun frequency(hz: Double): String {
        val text = if (hz >= 100) "${hz.roundToInt()} Hz" else "${NumberText.fixed(hz, 1)} Hz"
        if (hz < 20.0) return text
        val note = (12 * log2(hz / 440.0) + 57).roundToInt()
        return if (note in 0..127) "$text ≈ ${NesSystem.noteName(note)}" else text
    }

    /**
     * What a manual [period] does with [shape]: a repeating shape's rate (an audible buzz at short periods), a one-shot
     * shape's ramp time.
     */
    fun periodText(shape: S5bShape, period: Int): String = when {
        shape == S5bShape.OFF -> "${duration(rampSeconds(period))} per ramp (no shape chosen)"
        shape.repeats -> "${frequency(cycleHz(shape, period))} (${duration(rampSeconds(period))} per ramp)"
        else -> "${duration(rampSeconds(period))} ramp"
    }

    /**
     * What auto-period [octave] makes of [shape] against the note: the engine shifts the note's tone period right by
     * [octave] (left when negative); a tone period P sounds CPU / 32P, a saw at period E repeats at CPU / 512E (a triangle
     * at half that), so octave 4 puts a saw in unison with the note and octave 5 a triangle.
     */
    fun octaveText(shape: S5bShape, octave: Int): String {
        if (shape == S5bShape.OFF) return "period = note period ${shift(octave)} (no shape chosen)"
        if (!shape.repeats) {
            return "ramp = ${if (octave >= 0) "1/${1 shl octave.coerceAtMost(8)}" else "${1 shl (-octave).coerceAtMost(8)}×"} of 16 note cycles"
        }
        val rel = octave - if (shape.triangle) 5 else 4
        val pitch = when {
            rel == 0 -> "in unison with the note"
            rel > 0 -> "$rel octave${if (rel == 1) "" else "s"} above the note"
            else -> "${-rel} octave${if (rel == -1) "" else "s"} below the note"
        }
        return "a ${if (shape.triangle) "triangle" else "saw"} $pitch"
    }

    private fun shift(octave: Int) = when {
        octave > 0 -> "÷ ${1 shl octave}"
        octave < 0 -> "× ${1 shl -octave}"
        else -> "itself"
    }

    /** A mixer value as the editor writes it. */
    fun mixerText(value: Int): String = when (value) {
        0 -> "tone+noise"
        1 -> "noise"
        2 -> "tone"
        3 -> "off"
        else -> "?"
    }

    /** Whether [ins]'s mixer lets noise through on some frame (an empty mixer is tone only). */
    fun usesNoise(ins: NesInstrument): Boolean = FamiStudioEnvelopes.effective(ins.mixer, Kind.MIXER).values.any { it == 0 || it == 1 }

    /** One line for the Instrument view: the sound's hardware envelope in words. */
    fun summary(e: S5bEnvelope): String = when {
        e.shape == S5bShape.OFF -> "no hardware envelope"
        e.autoPitch -> "${e.shape.label.lowercase()}, auto octave ${e.octave}"
        else -> "${e.shape.label.lowercase()}, period ${e.period}"
    }

    // ---- warnings ------------------------------------------------------------------------------------------------

    /**
     * Where the squares' shared envelope and noise generators make one sound play another's settings, and volume
     * effects that a hardware envelope ignores. [song] is the arrangement of a structurally valid 5B song.
     */
    fun warnings(song: Song): List<String> {
        val out = ArrayList<String>()
        val fs = FsSong.of(song)
        val expansion = song.nes.expansion
        val squares = NesRules.S5B_SQUARE1 until fs.channels
        fun ins(slot: Int) = fs.instruments[slot - 1]
        fun envOn(slot: Int) = slot != 0 && ins(slot).let { it.chip == NesChip.S5B && it.s5b.shape != S5bShape.OFF }
        fun where(ch: Int, row: Int): String {
            val o = (0 until fs.orders).last { fs.orderStart[it] <= row }
            return "${Songs.patternLabel(song, fs.patternOfOrder[o]).replaceFirstChar { it.uppercase() }}, " +
                "${NesRules.channelName(expansion, ch)} row ${row - fs.orderStart[o]}"
        }
        val sounding = IntArray(fs.channels)
        val note = IntArray(fs.channels)
        val reported = HashSet<String>()
        for (row in 0 until fs.totalRows) {
            for (ch in squares) {
                val n = fs.notes[ch][row] ?: continue
                if (n.isMusical) {
                    if (n.instrument != 0) sounding[ch] = n.instrument
                    note[ch] = n.value
                } else if (n.value == FsNote.STOP) sounding[ch] = 0
                if (n.hasVolume && envOn(sounding[ch]) && reported.add("volume $ch ${sounding[ch]}")) {
                    out += "${where(ch, row)}: volume effects do not change sound ${hex2(sounding[ch])}: its 5B hardware envelope " +
                        "sets the level (the volume envelope, Cxx and Axy are ignored while it plays)"
                }
            }
            // The envelope generator: the highest square sounding an envelope sound sets shape and period for all.
            val env = squares.filter { envOn(sounding[it]) }
            if (env.size > 1) {
                val owner = env.last()
                val own = ins(sounding[owner]).s5b
                for (other in env.dropLast(1)) {
                    val e = ins(sounding[other]).s5b
                    val differs = e.shape != own.shape || e.autoPitch != own.autoPitch ||
                        (if (own.autoPitch) e.octave != own.octave || note[other] != note[owner] else e.period != own.period)
                    if (differs && reported.add("env ${sounding[other]} ${sounding[owner]}")) {
                        out += "${where(other, row)}: sound ${hex2(sounding[other])} and ${NesRules.channelName(expansion, owner)}'s sound " +
                            "${hex2(sounding[owner])} share the 5B's one envelope generator: while both sound, " +
                            "${NesRules.channelName(expansion, owner)}'s shape and period play on both (the highest square sets " +
                            "them); give them the same envelope and note, or keep them apart"
                    }
                }
            }
            // The noise generator: the highest sounding square sets the rate (Square 1 only with noise in its mixer).
            val source = squares.lastOrNull { sounding[it] != 0 && (it != NesRules.S5B_SQUARE1 || usesNoise(ins(sounding[it]))) }
            if (source != null) {
                val rate = FamiStudioEnvelopes.effective(ins(sounding[source]).noise, Kind.NOISE)
                for (ch in squares) {
                    if (ch == source || sounding[ch] == 0 || !usesNoise(ins(sounding[ch]))) continue
                    if (FamiStudioEnvelopes.effective(ins(sounding[ch]).noise, Kind.NOISE) == rate) continue
                    if (!reported.add("noise ${sounding[ch]} ${sounding[source]}")) continue
                    val quirk = if (!usesNoise(ins(sounding[source]))) ", even without noise in its mixer (FamiStudio's engine counts Squares 2 and 3 always)" else ""
                    out += "${where(ch, row)}: sound ${hex2(sounding[ch])} plays noise, but ${NesRules.channelName(expansion, source)}'s " +
                        "sound ${hex2(sounding[source])} sets the 5B's one noise rate$quirk; give both the same noise envelope or play " +
                        "the noise on the highest square"
                }
            }
            for (ch in squares) if ((fs.notes[ch][row]?.cutDelay ?: -1) >= 0) sounding[ch] = 0
        }
        return out
    }

    // ---- shared state at a song position ---------------------------------------------------------------------------

    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /**
     * Who drives the 5B's envelope and noise generators when row [row] of order [order] plays, as straight play reaches
     * it: a tag on those squares' lane headers and one inspector line each. Rows are taken in play order (orders that
     * play, then rows, squares in channel order); a sound loads (and with a manual period sets the shared period) on a
     * note with an instrument number and on each order's first note, `Ixx`/`Jyy` set the period. Cached per position.
     */
    fun state(song: Song, order: Int, row: Int): SharedState? {
        if (song.sound !is NesSound || song.nes.expansion != NesExpansion.S5B || order !in song.orders.indices) return null
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val state = compute(song, order, row)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }

    private fun compute(song: Song, order: Int, row: Int): SharedState {
        val expansion = song.nes.expansion
        val channels = expansion.channels
        val squares = NesRules.S5B_SQUARE1 until channels
        val bank = song.nes.instruments
        val slot = IntArray(channels)
        val note = IntArray(channels)
        val sounding = BooleanArray(channels)
        var period = 0 // famistudio_music_play clears the engine's manual period
        var periodFrom = "the start (0)"
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns[song.orders[o]]
            val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
            val loaded = BooleanArray(channels)
            for (r in 0..last) {
                val cells = squares.map { p.tracks.getOrNull(it)?.getOrNull(r) ?: Cell.BLANK }
                val effect = NesRules.envPeriod(cells) ?: -1
                if (effect >= 0) {
                    period = effect
                    periodFrom = "${hex2(o)}:${hex2(r)} (Ixx/Jyy)"
                }
                for ((k, ch) in squares.withIndex()) {
                    val cell = cells[k]
                    if (cell.note >= 0) {
                        val load = cell.instrument != 0 || (!loaded[ch] && slot[ch] != 0)
                        if (cell.instrument != 0) slot[ch] = cell.instrument
                        note[ch] = cell.note
                        sounding[ch] = true
                        loaded[ch] = true
                        val ins = bank.getOrNull(slot[ch] - 1)
                        if (load && effect < 0 && ins != null && ins.chip == NesChip.S5B && !ins.s5b.autoPitch) {
                            period = ins.s5b.period
                            periodFrom = "${hex2(o)}:${hex2(r)} (sound ${hex2(slot[ch])})"
                        }
                    } else if (cell.note == Cell.CUT) {
                        val ins = bank.getOrNull(slot[ch] - 1)
                        sounding[ch] = ins != null && ins.volume.release >= 0 && ins.volume.loop >= 0
                    }
                    if (cell.hasEffect && cell.effect == NesRules.FX_NOTE_CUT && r < last) sounding[ch] = false
                }
            }
        }
        fun ins(ch: Int) = bank.getOrNull(slot[ch] - 1)?.takeIf { it.chip == NesChip.S5B }
        val owner = squares.lastOrNull { sounding[it] && ins(it)?.s5b?.shape?.let { s -> s != S5bShape.OFF } == true }
        val source = squares.lastOrNull { sounding[it] && ins(it) != null && (it != NesRules.S5B_SQUARE1 || usesNoise(ins(it)!!)) }
        val tags = List(channels) { ch ->
            when {
                ch == owner && ch == source -> "ENV+NZ"
                ch == owner -> "ENV"
                ch == source -> "NOISE"
                else -> null
            }
        }
        val envLine = if (owner == null) "5B envelope: no square sounds an envelope sound" else {
            val e = ins(owner)!!.s5b
            val how = if (e.autoPitch) "auto, octave ${e.octave}: ${octaveText(e.shape, e.octave)}" else
                "manual period $period from $periodFrom: ${periodText(e.shape, period)}"
            "5B envelope: ${NesRules.channelName(expansion, owner)}'s sound ${hex2(slot[owner])} sets it for every envelope sound " +
                "(${e.shape.label.lowercase()}, $how)"
        }
        val noiseLine = if (source == null) "5B noise: no square sets the noise rate" else {
            val quirk = if (!usesNoise(ins(source)!!)) " (though its mixer has no noise: FamiStudio's engine counts Squares 2 and 3 always)" else ""
            "5B noise: the rate follows ${NesRules.channelName(expansion, source)}'s sound ${hex2(slot[source])} noise envelope$quirk"
        }
        return SharedState(tags, listOf(envLine, noiseLine))
    }
}
