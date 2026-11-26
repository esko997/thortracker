package org.thortracker.system.midi

import org.thortracker.model.Cell

/** Scientific semitones, normalized dynamics, and track-local named parameter slots. */
sealed interface MusicalEvent {
    data class Attack(val pitch: Int, val strength: Double) : MusicalEvent
    data class Release(val pitch: Int) : MusicalEvent
    data class Pitch(val semitones: Double) : MusicalEvent
    data class PitchStep(val semitones: Double) : MusicalEvent
    data class ExpressionStep(val amount: Double) : MusicalEvent
    data class Parameter(val slot: Int, val level: Double) : MusicalEvent
    data object Cut : MusicalEvent
}

data class TimedEvent(val offset: Long, val trackId: Int, val event: MusicalEvent)

/** All emissions end within this row. Later columns replace earlier commands in the same family. */
fun musicalRow(track: MidiTrack, cell: Cell, duration: Long): List<TimedEvent> {
    val result = mutableListOf<TimedEvent>()
    fun emit(at: Long, event: MusicalEvent) { result += TimedEvent(at, track.id, event) }
    var delay = 0L
    var velocity = track.velocity
    var strikes = 1
    var echo = false
    var arp = 0
    var slide: Double? = null
    var expression: Int? = null
    for (column in 0..cell.more.size) {
        val value = cell.effectParam(column)
        when (cell.effectCode(column)) {
            0 -> if (value != 0) arp = value
            1 -> slide = value / 100.0
            2 -> slide = -value / 100.0
            3 -> expression = value
            4 -> expression = -value
            5 -> { strikes = value.coerceIn(1, 16); echo = false }
            6 -> delay = duration * value / 256
            7 -> { strikes = 1 + value.coerceIn(1, 8); echo = true }
            8 -> velocity = value.coerceIn(1, 127)
            9, 10 -> emit(0, MusicalEvent.Parameter(cell.effectCode(column) - 9, value / 127.0))
        }
    }
    if (cell.note >= 0) emit(delay, MusicalEvent.Pitch(0.0))
    // Relative motion is resolved at dispatch: other tracks can write the same destination
    // between increments. Each of the five increments contributes one fifth of the row amount.
    for (i in 1..5) {
        val at = duration * i / 6
        slide?.let { emit(at, MusicalEvent.PitchStep(it / 5)) }
        expression?.let { emit(at, MusicalEvent.ExpressionStep(it / (5 * 127.0))) }
    }
    if (cell.note == Cell.CUT) emit(delay, MusicalEvent.Cut)
    if (cell.note >= 0) {
        if (arp != 0) strikes = maxOf(3, strikes)
        val span = duration - delay
        for (i in 0 until strikes) {
            val pitch = cell.note + when (i % 3) { 1 -> arp shr 4; 2 -> arp and 15; else -> 0 }
            if (pitch > 115) continue
            val start = delay + span * i / strikes
            val length = (span / strikes * track.gate / 100).coerceAtLeast(1)
            val strength = velocity / 127.0 * if (echo) (strikes - i).toDouble() / strikes else 1.0
            emit(start, MusicalEvent.Attack(pitch, strength))
            emit((start + length).coerceAtMost(duration - 1), MusicalEvent.Release(pitch))
        }
    }
    return result.sortedBy { it.offset }
}
