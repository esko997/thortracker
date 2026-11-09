package org.thortracker.audio

import org.thortracker.editor.EditorSession
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * Song playback in document terms, over any [TrackerAudio]: Play/Stop, the editor's pattern loop (L2+Y) and parked
 * orders. The engine always hears [heard]: the song's arrangement, or only the looped order. Positions it reports
 * are mapped back to document orders ([position]). Platform concerns (audio focus, refresh scheduling, errors) go
 * through [Host].
 */
class Transport(private val session: EditorSession, private val host: Host = object : Host {}) {
    interface Host {
        /** Playback may start now (e.g. Android audio focus was granted); false refuses this start. */
        fun mayPlay(): Boolean = true

        /** Playback was started or stopped on request; the frontend updates its transport display. */
        fun transportChanged(starting: Boolean) {}

        /** There is no engine yet. */
        fun audioMissing() {}
    }

    /** The engine; setting it hands it the current song as heard. */
    var audio: TrackerAudio? = null
        set(value) {
            field = value
            value?.update(heard(session.song))
        }

    /** Playback intent; [position] clears it only after the engine has settled stopped. */
    var requested = false
        private set

    /** Order whose pattern playback loops, or -1. Editor-only; Play moves it to the order played. */
    var loopOrder = -1
        private set

    /** The pattern [loopOrder] played when the loop was set, so order inserts/deletes/moves can follow it. */
    private var loopPattern = -1

    /** The loop was started only to hear a parked order (not by the loop control), so stopping playback ends it. */
    private var loopForParked = false

    /** What the engine plays: the song, or only the looped order ([org.thortracker.system.SoundSystem.patternLoop]). */
    fun heard(song: Song): Song = if (loopOrder >= 0) song.system.patternLoop(song, loopOrder) else song

    /**
     * The engine's position in document terms: while looping, its single order is [loopOrder]; otherwise it counts
     * played orders only, so parked ones are skipped when mapping back ([Songs.playedOrders]).
     */
    fun position(): PlaybackPosition? = audio?.position()?.let {
        if (!it.playing && !it.pending) requested = false
        if (loopOrder >= 0) it.copy(order = loopOrder, looping = true)
        else it.copy(order = Songs.playedOrders(session.song).getOrElse(it.order) { _ -> it.order })
    }

    /** Call for every document change: the loop follows its pattern, and the engine gets the new song. */
    fun songChanged(song: Song) {
        if (loopOrder >= 0 && song.orders.getOrNull(loopOrder) != loopPattern) {
            val moved = Songs.nearestOrderPlaying(song.orders, loopPattern, loopOrder)
            loopOrder = if (moved >= 0) moved else loopOrder.coerceIn(0, song.orders.size - 1)
            loopPattern = song.orders[loopOrder]
        }
        audio?.update(heard(song))
    }

    /** Stopping is unconditional; starting plays document [order] from [row]. */
    fun toggle(order: Int, row: Int) = if (requested) stop() else start(order, row)

    fun start(order: Int, row: Int) {
        val engine = audio ?: return host.audioMissing()
        if (!host.mayPlay()) return
        requested = true
        val target = order.coerceIn(0, session.song.orders.size - 1)
        if (loopForParked && target !in session.song.parkedOrders) {
            loopForParked = false
            loopOrder = -1
            engine.update(session.song)
        }
        if (loopOrder < 0 && target in session.song.parkedOrders) {
            // The song skips a parked order; playing it on its own means looping it until playback stops.
            loopAt(target)
            loopForParked = true
            engine.update(heard(session.song))
            session.note("Order ${EditorSession.hex2(target)} is parked: looping it on its own until you stop")
        }
        // While looping, Play loops whichever order it starts: the engine's song is that order alone.
        if (loopOrder >= 0 && loopOrder != target) { loopAt(target); engine.update(heard(session.song)) }
        engine.play(if (loopOrder >= 0) 0 else Songs.playedOrders(session.song).indexOf(target), maxOf(0, row))
        host.transportChanged(true)
    }

    fun stop() {
        requested = false
        audio?.stop()
        if (loopForParked) {
            loopForParked = false
            loopOrder = -1
            audio?.update(session.song)
        }
        host.transportChanged(false)
    }

    /** Stops without ending a parked-order preview or notifying the host (audio focus loss, the app pausing). */
    fun halt() {
        audio?.stop()
        requested = false
    }

    /**
     * Pattern loop on/off for document order [activeOrder]. Turning it on while that order plays keeps the current
     * row; while another order plays, the loop starts at row 00. Turning it off keeps playing on through the song
     * from the same row (or stops, if the looped order is parked). Stopped, it only arms the loop for the next Play.
     */
    fun toggleLoop(activeOrder: Int) {
        val engine = audio ?: return host.audioMissing()
        val playing = requested
        val heardNow = position()
        loopForParked = false
        if (loopOrder >= 0) {
            val was = loopOrder
            loopOrder = -1
            engine.update(session.song)
            when {
                // A parked order is not part of the song, so there is nowhere to play on to.
                playing && was in session.song.parkedOrders -> {
                    stop()
                    session.note("Pattern loop off; order ${EditorSession.hex2(was)} is parked, so playback stopped")
                }
                playing -> { session.note("Pattern loop off"); start(was, heardNow?.row ?: 0) }
                else -> session.note("Pattern loop off")
            }
        } else {
            loopAt(activeOrder)
            engine.update(heard(session.song))
            session.note("Looping pattern ${EditorSession.hex2(session.song.orders[loopOrder])} (order ${EditorSession.hex2(loopOrder)})")
            if (playing) start(loopOrder, if (heardNow?.order == loopOrder) heardNow.row else 0)
        }
    }

    private fun loopAt(order: Int) {
        loopOrder = order.coerceIn(0, session.song.orders.size - 1)
        loopPattern = session.song.orders[loopOrder]
    }
}
