package org.thortracker.system.ay

import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SharedState
import org.thortracker.system.nes.NesEnvelope

/**
 * What a channel asks of the shared hardware envelope: [shape], and its period — auto ([octave] above [note]) or the
 * manual [period]. Two notes with equal settings sound the same envelope, so handing it over between them is harmless.
 */
data class AyEnvelopeUse(val shape: Int, val auto: Boolean, val octave: Int, val period: Int, val note: Int) {
    /** "shape C, auto J00 on A-2" / "shape 9, manual 0400". */
    fun text(system: AySystem): String =
        "${AyRules.shapeText(shape)}, " + if (auto) "auto ${AyRules.autoText(octave)} on ${system.noteName(note)}" else "manual ${NumberText.hex(period, 4)}"

    /** The envelope period the chip gets (auto: from the note's pitch, before arpeggios, slides and vibrato). */
    fun envelopePeriod(variant: AyVariant): Int =
        if (auto) AyRules.autoEnvelopePeriod(variant, note * 64, octave, shape) else period
}

/**
 * The shared resources at one row of straight play: the envelope's owner channel ([envelopeOwner], −1 = none) with what
 * it asks ([envelope]), the channels playing the envelope ([envelopeUsers], bit per channel), the noise owner
 * ([noiseOwner], −1 = none asks for a period this tick) and the period R6 holds ([noisePeriod], −1 = never set, with
 * [noiseSetter] the channel that set it), and the channels whose noise gate is open ([noiseUsers]).
 */
class AyRowState(
    val envelopeOwner: Int,
    val envelope: AyEnvelopeUse?,
    val envelopeUsers: Int,
    val noiseOwner: Int,
    val noisePeriod: Int,
    val noiseSetter: Int,
    val noiseUsers: Int,
)

/**
 * Straight play of an AY song, tick by tick, as far as the shared noise and envelope depend on it (docs/AUDIO_ENGINE.md,
 * "AY-3-8910 / YM2149: Player" and "Shared noise and envelope"): rows in order (jumps ignored), `Fxx` speeds, `Gxx`
 * delays, `Sxx` cuts, key-on/off, the volume, mixer and noise envelopes, `Cxx`/`Axy` volume, the note settings, and the
 * most-recent-key-on ownership rule. Pitch effects are not followed (an auto envelope's period is read from the note).
 * [AySharedState] keeps a row snapshot per position for the Pattern view; [AyValidator] reads the [events].
 */
internal class AyStraightPlay(private val song: Song) {
    /** Something straight play found worth a warning, at ([order] in the played list, [row]) on [channel]. */
    sealed class Event(val order: Int, val row: Int, val channel: Int)

    /** [channel]'s key-on took the envelope ([taker]) from [from], still using it with [held]. */
    class EnvelopeTaken(order: Int, row: Int, channel: Int, val taker: AyEnvelopeUse, val from: Int, val held: AyEnvelopeUse) :
        Event(order, row, channel)

    /** [channel]'s key-on took the noise period ([period]) from [from], still asking for [held]. */
    class NoiseTaken(order: Int, row: Int, channel: Int, val period: Int, val from: Int, val held: Int) : Event(order, row, channel)

    /** Note settings ([code] first) on a row where the channel plays nothing. */
    class IdleSettings(order: Int, row: Int, channel: Int, val code: Int) : Event(order, row, channel)

    /** A period setting ([code] = `H`, `I` or `J`) for a note without an envelope shape. */
    class PeriodWithoutShape(order: Int, row: Int, channel: Int, val code: Int) : Event(order, row, channel)

    /** `G` [delay] at or beyond the row's [speed]: the row never plays. */
    class DelayNeverPlays(order: Int, row: Int, channel: Int, val delay: Int, val speed: Int) : Event(order, row, channel)

    val events = ArrayList<Event>()

    private val sound = song.ay
    private val ch = Array(AyRules.CHANNELS) { Channel() }
    private var stamps = 0
    private var envelopeOwner = -1
    private var noiseOwner = -1
    private var noisePeriod = -1
    private var noiseSetter = -1

    private class Channel {
        var keyed = false
        var phase = OFF
        var slot = 0
        var ins: AyInstrument? = null
        var volumePos = 0
        var mixerPos = 0
        var noisePos = 0
        var volume8 = 120
        var slideUp = 0
        var slideDown = 0
        var stamp = 0
        var shape = 0
        var auto = true
        var octave = 0
        var period = 0
        var mixerOverride = -1
        var noiseOverride = -1
        var note = 0
        var portamento = 0
        var cut = -1
        var delayed: Cell? = null
        var delayTick = -1

        // This tick's evaluation.
        var sounds = false
        var usesEnvelope = false
        var noiseOn = false
        var noise = -1

        fun use() = AyEnvelopeUse(shape, auto, if (auto) octave else 0, if (auto) 0 else period, if (auto) note else -1)
    }

    /**
     * Plays the song's orders at [orders] (indices into [Song.orders], in play order) once from a reset player; calls
     * [snapshot] for each row (its position in [orders] and the row) with the shared state once every channel's row
     * has run (after the row's latest `Gxx` delay within the speed).
     */
    fun run(orders: List<Int>, snapshot: ((Int, Int, AyRowState) -> Unit)? = null) {
        var speed = song.ticksPerRow
        orders.forEachIndexed { position, o ->
            val pattern = song.patterns[song.orders[o]]
            for (row in 0 until pattern.length) {
                for (c in 0 until AyRules.CHANNELS) {
                    val cell = pattern.tracks[c][row]
                    for (col in 0..cell.more.size) if (cell.effectCode(col) == AyRules.FX_SPEED && cell.effectParam(col) > 0) speed = cell.effectParam(col)
                }
                var snapTick = 0
                for (c in 0 until AyRules.CHANNELS) {
                    val chan = ch[c]
                    val cell = pattern.tracks[c][row]
                    chan.cut = -1
                    chan.delayed = null
                    var delay = 0
                    for (col in 0..cell.more.size) if (cell.effectCode(col) == AyRules.FX_DELAY && cell.effectParam(col) > 0) delay = cell.effectParam(col)
                    if (delay > 0) {
                        if (delay >= speed) events += DelayNeverPlays(position, row, c, delay, speed)
                        else {
                            chan.delayed = cell
                            chan.delayTick = delay
                            snapTick = maxOf(snapTick, delay)
                        }
                    } else runRow(position, row, c, cell)
                }
                for (tick in 0 until speed) {
                    if (tick > 0) for (c in 0 until AyRules.CHANNELS) {
                        val chan = ch[c]
                        val cell = chan.delayed
                        if (cell != null && chan.delayTick == tick) {
                            chan.delayed = null
                            runRow(position, row, c, cell)
                        }
                    }
                    for (c in 0 until AyRules.CHANNELS) if (ch[c].cut == tick) keyOff(ch[c])
                    for (chan in ch) evaluate(chan)
                    resolve(position, row)
                    if (tick == snapTick && snapshot != null) snapshot(position, row, state())
                    for (chan in ch) advance(chan)
                }
            }
        }
    }

    private fun state(): AyRowState {
        var envelopeUsers = 0
        var noiseUsers = 0
        for (c in 0 until AyRules.CHANNELS) {
            if (ch[c].usesEnvelope) envelopeUsers = envelopeUsers or (1 shl c)
            if (ch[c].noiseOn) noiseUsers = noiseUsers or (1 shl c)
        }
        val owner = envelopeOwner
        return AyRowState(owner, if (owner >= 0) ch[owner].use() else null, envelopeUsers, noiseOwner, noisePeriod, noiseSetter, noiseUsers)
    }

    /** "Running a row" of [c]: instrument, effects in column order, then the note; settings apply to the note. */
    private fun runRow(position: Int, row: Int, c: Int, cell: Cell) {
        val chan = ch[c]
        if (cell.instrument != 0) chan.slot = cell.instrument
        var settings = 0
        var firstSetting = -1
        var periodSetting = -1
        for (col in 0..cell.more.size) {
            val code = cell.effectCode(col)
            val param = cell.effectParam(col)
            if (code == 0 && param == 0) continue
            when (code) {
                AyRules.FX_VOLUME -> chan.volume8 = minOf(param, 15) * 8
                AyRules.FX_VOLUME_SLIDE -> { chan.slideUp = param shr 4; chan.slideDown = param and 0x0F }
                AyRules.FX_CUT -> chan.cut = param
                AyRules.FX_PORTAMENTO -> chan.portamento = param
                // A slide replaces the glide (the player has one slide mode per channel).
                AyRules.FX_SLIDE_UP, AyRules.FX_SLIDE_DOWN -> chan.portamento = 0
            }
            if (code in AyRules.NOTE_SETTINGS) {
                settings++
                if (firstSetting < 0) firstSetting = code
                if (periodSetting < 0 && (code == AyRules.FX_ENV_HIGH || code == AyRules.FX_ENV_LOW || code == AyRules.FX_ENV_AUTO)) periodSetting = code
            }
        }
        var keyedOn = false
        when {
            cell.note == Cell.CUT -> keyOff(chan)
            cell.note >= 0 && chan.portamento > 0 && chan.phase == ON -> chan.note = cell.note
            cell.note >= 0 -> keyedOn = keyOn(chan, cell)
        }
        if (!keyedOn) applySettings(chan, cell)
        if (settings == 0) return
        if (!keyedOn && chan.phase == OFF) events += IdleSettings(position, row, c, firstSetting)
        else if (periodSetting >= 0 && chan.shape == 0) events += PeriodWithoutShape(position, row, c, periodSetting)
    }

    private fun keyOn(chan: Channel, cell: Cell): Boolean {
        val ins = sound.instrument(chan.slot) ?: return false
        chan.ins = ins
        chan.volumePos = 0
        chan.mixerPos = 0
        chan.noisePos = 0
        chan.shape = ins.envelope.shape
        // The image's fields: only the active mode's octave or period (the other is 0), as the player loads them.
        chan.auto = ins.envelope.auto
        chan.octave = ins.envelope.imageOctave
        chan.period = ins.envelope.imagePeriod
        chan.mixerOverride = -1
        chan.noiseOverride = -1
        applySettings(chan, cell)
        chan.phase = ON
        chan.note = cell.note
        chan.stamp = ++stamps
        chan.keyed = true
        return true
    }

    /** The row's note settings (`E`, `H`, `I`, `J`, `V`, `W`) in column order onto [chan]'s current ones. */
    private fun applySettings(chan: Channel, cell: Cell) {
        for (col in 0..cell.more.size) {
            val code = cell.effectCode(col)
            val param = cell.effectParam(col)
            when (code) {
                AyRules.FX_ENV_SHAPE -> chan.shape = param
                AyRules.FX_ENV_HIGH -> { chan.period = (param shl 8) or (chan.period and 0xFF); chan.auto = false }
                AyRules.FX_ENV_LOW -> { chan.period = (chan.period and 0xFF00) or param; chan.auto = false }
                AyRules.FX_ENV_AUTO -> { chan.auto = true; chan.octave = AyRules.octaveOf(param) }
                AyRules.FX_MIXER -> chan.mixerOverride = param
                AyRules.FX_NOISE -> chan.noiseOverride = param
            }
        }
    }

    /** OFF or `Sxx`: only a note in its held phase reacts (a released note ignores a second key-off). */
    private fun keyOff(chan: Channel) {
        if (chan.phase != ON) return
        val volume = chan.ins?.volume
        if (volume != null && volume.release >= 0) {
            chan.phase = RELEASED
            chan.volumePos = volume.release
        } else chan.phase = OFF
    }

    private fun evaluate(chan: Channel) {
        val ins = chan.ins
        if (!chan.keyed || ins == null) {
            chan.sounds = false; chan.usesEnvelope = false; chan.noiseOn = false; chan.noise = -1
            return
        }
        val level = if (chan.phase == OFF) 0 else value(ins.volume, chan.volumePos, 15)
        val v = (level * (chan.volume8 shr 3) + 14) / 15
        chan.sounds = chan.phase != OFF && v > 0
        chan.usesEnvelope = chan.sounds && chan.shape != 0
        val mixer = if (chan.mixerOverride >= 0) chan.mixerOverride else value(ins.mixer, chan.mixerPos, AyRules.MIXER_DEFAULT)
        chan.noise = if (chan.noiseOverride >= 0) chan.noiseOverride else value(ins.noise, chan.noisePos, -1)
        chan.noiseOn = chan.sounds && mixer and AyRules.MIXER_NOISE != 0
    }

    /** The owners by the most recent key-on; a key-on taking one from a channel still using it differently is an event. */
    private fun resolve(position: Int, row: Int) {
        var env = -1
        var noise = -1
        for (c in 0 until AyRules.CHANNELS) {
            val chan = ch[c]
            if (chan.usesEnvelope && (env < 0 || chan.stamp > ch[env].stamp)) env = c
            if (chan.noiseOn && chan.noise >= 0 && (noise < 0 || chan.stamp > ch[noise].stamp)) noise = c
        }
        val oldEnv = envelopeOwner
        if (env >= 0 && oldEnv >= 0 && env != oldEnv && ch[oldEnv].usesEnvelope && ch[env].stamp > ch[oldEnv].stamp) {
            val taker = ch[env].use()
            val held = ch[oldEnv].use()
            if (taker.shape != held.shape || taker.envelopePeriod(sound.variant) != held.envelopePeriod(sound.variant)) {
                events += EnvelopeTaken(position, row, env, taker, oldEnv, held)
            }
        }
        envelopeOwner = env
        val oldNoise = noiseOwner
        if (noise >= 0 && oldNoise >= 0 && noise != oldNoise && ch[oldNoise].noiseOn && ch[oldNoise].noise >= 0 &&
            ch[noise].stamp > ch[oldNoise].stamp && ch[noise].noise != ch[oldNoise].noise
        ) {
            events += NoiseTaken(position, row, noise, ch[noise].noise, oldNoise, ch[oldNoise].noise)
        }
        noiseOwner = noise
        if (noise >= 0) {
            noisePeriod = ch[noise].noise
            noiseSetter = noise
        }
    }

    private fun advance(chan: Channel) {
        val ins = chan.ins ?: return
        if (!chan.keyed) return
        if (chan.slideUp != 0 || chan.slideDown != 0) chan.volume8 = (chan.volume8 + chan.slideUp - chan.slideDown).coerceIn(0, 120)
        val released = chan.phase == RELEASED
        chan.volumePos = step(ins.volume, chan.volumePos, released)
        chan.mixerPos = step(ins.mixer, chan.mixerPos, released)
        chan.noisePos = step(ins.noise, chan.noisePos, released)
        val v = ins.volume.values
        if (released && v.isNotEmpty() && chan.volumePos == v.size - 1 && v[v.size - 1] == 0) chan.phase = OFF
    }

    companion object {
        private const val OFF = 0
        private const val ON = 1
        private const val RELEASED = 2

        /** [env]'s value at [pos], or [empty] without one. */
        fun value(env: NesEnvelope, pos: Int, empty: Int): Int = if (env.values.isEmpty()) empty else env.values[pos.coerceIn(0, env.values.size - 1)]

        /** FamiStudio's envelope step (as the Mega Drive's PSG envelopes). */
        fun step(env: NesEnvelope, pos: Int, released: Boolean): Int {
            val n = env.values.size
            if (n == 0) return 0
            val next = pos + 1
            if (!released && env.release >= 0 && next == env.release) return env.loop
            if (next >= n) return if (env.loop >= 0 && env.release < 0) env.loop else n - 1
            return next
        }
    }
}

/**
 * The Pattern view's reading of the shared noise and envelope at a position ([AySystem.sharedState]): lane tags `ENV`
 * (owner), `env` (playing another channel's envelope), `NZ` (noise owner), `nz` (noise from another channel's period)
 * and inspector lines naming the owners with their settings. Straight play runs once per song: every played order's
 * rows are kept, a parked order is played on its own when asked for; the last answer is cached for the per-frame calls.
 */
object AySharedState {
    private var cacheSong: Song? = null
    private var rows: Array<Array<AyRowState?>?> = emptyArray()
    private var lastOrder = -1
    private var lastRow = -1
    private var lastState: SharedState? = null

    /** The state when row [row] of order [order] (a [Song.orders] index) plays, or null outside the song. */
    fun at(song: Song, order: Int, row: Int): AyRowState? {
        if (song.sound !is AySound || order !in song.orders.indices) return null
        if (song !== cacheSong) {
            cacheSong = song
            lastState = null
            rows = arrayOfNulls(song.orders.size)
            val played = Songs.playedOrders(song)
            fill(song, played)
        }
        if (rows[order] == null) fill(song, listOf(order))
        return rows[order]?.getOrNull(row)
    }

    private fun fill(song: Song, orders: List<Int>) {
        for (o in orders) rows[o] = arrayOfNulls(song.patterns[song.orders[o]].length)
        AyStraightPlay(song).run(orders) { position, row, state -> rows[orders[position]]!![row] = state }
    }

    fun state(song: Song, order: Int, row: Int): SharedState? {
        if (song.sound !is AySound) return null
        if (song === cacheSong && order == lastOrder && row == lastRow) lastState?.let { return it }
        val s = at(song, order, row) ?: return null
        val system = song.ay.variant.system
        val tags = List(AyRules.CHANNELS) { c ->
            val env = when {
                c == s.envelopeOwner -> "ENV"
                s.envelopeUsers shr c and 1 != 0 -> "env"
                else -> null
            }
            val noise = when {
                c == s.noiseOwner -> "NZ"
                s.noiseUsers shr c and 1 != 0 -> "nz"
                else -> null
            }
            if (env != null && noise != null) "$env $noise" else env ?: noise
        }
        val lines = ArrayList<String>(2)
        val owner = s.envelopeOwner
        val use = s.envelope
        lines += if (owner < 0 || use == null) "Envelope: free (no sounding note uses it)"
        else {
            val ep = use.envelopePeriod(song.ay.variant)
            val hz = NumberText.fixed(AyRules.envelopeHz(song.ay.variant, ep, use.shape), 1)
            val borrowers = (0 until AyRules.CHANNELS).filter { it != owner && s.envelopeUsers shr it and 1 != 0 }
            "Envelope: ${AyRules.CHANNEL_NAMES[owner]} owns it (${use.text(system)}: period ${NumberText.hex(ep, 4)}, $hz Hz)" +
                if (borrowers.isEmpty()) "" else "; ${borrowers.joinToString(" and ") { AyRules.CHANNEL_NAMES[it] }} play${if (borrowers.size == 1) "s" else ""} it"
        }
        val users = (0 until AyRules.CHANNELS).filter { s.noiseUsers shr it and 1 != 0 }
        lines += when {
            s.noiseOwner >= 0 -> {
                val others = users.filter { it != s.noiseOwner }
                "Noise: ${AyRules.CHANNEL_NAMES[s.noiseOwner]} owns it (period ${s.noisePeriod})" +
                    if (others.isEmpty()) "" else "; ${others.joinToString(" and ") { AyRules.CHANNEL_NAMES[it] }} play${if (others.size == 1) "s" else ""} it"
            }
            users.isNotEmpty() && s.noisePeriod >= 0 ->
                "Noise: ${users.joinToString(" and ") { AyRules.CHANNEL_NAMES[it] }} play${if (users.size == 1) "s" else ""} period ${s.noisePeriod}, " +
                    "last set by ${AyRules.CHANNEL_NAMES[s.noiseSetter]}"
            users.isNotEmpty() -> "Noise: ${users.joinToString(" and ") { AyRules.CHANNEL_NAMES[it] }} play${if (users.size == 1) "s" else ""} " +
                "the power-on period 0 (no note has asked for one yet)"
            else -> "Noise: free (no sounding note uses it)"
        }
        val state = SharedState(tags, lines)
        lastOrder = order; lastRow = row; lastState = state
        return state
    }
}
