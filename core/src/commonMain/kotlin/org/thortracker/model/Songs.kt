package org.thortracker.model

import org.thortracker.system.SoundSystems
import kotlin.math.abs

/** Factories and chip-neutral queries for [Song]; everything about the chip comes from the song's system. */
object Songs {
    const val ROWS = 64

    /** Highest note any system may use: B-9 (notes count semitones from C-0). */
    const val NOTE_LIMIT = 119

    private val NOTE_NAMES = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-")

    /** A new song of the default system. */
    fun blank(): Song = SoundSystems.DEFAULT.blank()

    /** The default system's demo: a complete looping arrangement exercising instruments, cuts and effects. */
    fun demo(): Song = SoundSystems.DEFAULT.demo()

    /** The order closest to [near] that plays [pattern] (earlier wins a tie), or -1 when no order plays it. */
    fun nearestOrderPlaying(orders: List<Int>, pattern: Int, near: Int): Int =
        orders.indices.filter { orders[it] == pattern }.minWithOrNull(compareBy({ abs(it - near) }, { it })) ?: -1

    fun blankPattern(song: Song, name: String = "Pattern"): Pattern = Pattern.blank(song.system.channels.size, ROWS, name)

    /** "C-0".."B-9" (semitones from C-0), "---" for no note, "OFF" for an explicit note cut ([Cell.CUT]). */
    fun noteName(note: Int): String = when {
        note == Cell.EMPTY -> "---"
        note == Cell.CUT -> "OFF"
        note in 0..NOTE_LIMIT -> NOTE_NAMES[note % 12] + (note / 12)
        else -> "???"
    }

    /** The document order indices song playback and export use, in order: every order that is not parked. */
    fun playedOrders(song: Song): List<Int> {
        if (song.parkedOrders.isEmpty()) return song.orders.indices.toList()
        val parked = song.parkedOrders.toHashSet()
        return song.orders.indices.filter { it !in parked }
    }

    fun patternLabel(song: Song, index: Int): String {
        val name = song.patterns.getOrNull(index)?.name
        return if (name.isNullOrBlank()) "pattern $index" else "pattern $index \"$name\""
    }
}
