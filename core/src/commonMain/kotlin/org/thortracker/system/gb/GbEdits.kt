package org.thortracker.system.gb

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex1
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Game Boy document edits: the typed instrument banks and the wave tables, as undoable [EditorSession] commands. */

fun Song.bank(kind: GbBank): List<GbInstrument> = HugeRules.bank(this, kind)

fun Song.withBank(kind: GbBank, list: List<GbInstrument>): Song = when (kind) {
    GbBank.PULSE -> copy(sound = gb.copy(pulse = list))
    GbBank.WAVE -> copy(sound = gb.copy(wave = list))
    GbBank.NOISE -> copy(sound = gb.copy(noise = list))
}

/** Instrument referenced by slot 1..15 in [kind]'s bank, or null for slot 0 / out of range. */
fun Song.instrument(kind: GbBank, slot: Int): GbInstrument? = bank(kind).getOrNull(slot - 1)

/** First channel whose instrument bank has [kind], or -1. */
@Suppress("UnusedReceiverParameter")
fun Song.firstChannelOf(kind: GbBank): Int = HugeRules.CHANNEL_KINDS.indexOf(kind)

/** A copied Game Boy sound or wave table. */
sealed interface GbClip : Clip {
    data class Sound(val instrument: GbInstrument) : GbClip
    data class Wave(val samples: List<Int>) : GbClip
}

fun EditorSession.setInstrument(kind: GbBank, slot: Int, instrument: GbInstrument): Boolean {
    if (slot !in 1..HugeRules.INSTRUMENT_SLOTS) return false
    return apply("Edit ${instrument.name.ifBlank { "sound" }}") { s ->
        val fixed = instrument.copy(kind = kind)
        s.withBank(kind, s.bank(kind).replaced(slot - 1, fixed))
    }
}

fun EditorSession.editInstrument(kind: GbBank, slot: Int, label: String, f: (GbInstrument) -> GbInstrument): Boolean {
    val cur = song.instrument(kind, slot) ?: return false
    return apply(label) { s -> s.withBank(kind, s.bank(kind).replaced(slot - 1, f(cur).copy(kind = kind))) }
}

fun EditorSession.copyInstrument(kind: GbBank, slot: Int) {
    val i = song.instrument(kind, slot) ?: return
    hold(GbClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

/** Pastes a copied sound; only into the same kind of bank, because parameters differ per kind. */
fun EditorSession.pasteInstrument(kind: GbBank, slot: Int): Boolean {
    val clip = clipboard as? GbClip.Sound ?: run { note("Clipboard holds no sound"); return false }
    if (clip.instrument.kind != kind) {
        note("Clipboard holds a ${kindName(clip.instrument.kind)} sound; pick a ${kindName(clip.instrument.kind)} slot")
        return false
    }
    val ok = setInstrument(kind, slot, clip.instrument)
    note("Pasted into sound ${hex2(slot)}")
    return ok
}

fun EditorSession.setWave(index: Int, samples: List<Int>, label: String = "Edit wave"): Boolean {
    if (samples.size != WaveShapes.SAMPLES) return false
    val clamped = samples.map { it.coerceIn(0, WaveShapes.MAX) }
    return apply(label) { s -> s.copy(sound = s.gb.copy(waves = s.gb.waves.replaced(index, clamped))) }
}

fun EditorSession.setWaveSample(index: Int, sample: Int, value: Int): Boolean {
    val w = song.gb.waves[index]
    if (sample !in w.indices) return false
    return setWave(index, w.replaced(sample, value.coerceIn(0, WaveShapes.MAX)), "Draw wave")
}

fun EditorSession.copyWave(index: Int) {
    hold(GbClip.Wave(song.gb.waves[index]))
    note("Copied wave ${hex1(index)}")
}

fun EditorSession.pasteWave(index: Int): Boolean {
    val clip = clipboard as? GbClip.Wave ?: run { note("Clipboard holds no wave"); return false }
    val ok = setWave(index, clip.samples, "Paste wave")
    note("Pasted into wave ${hex1(index)}")
    return ok
}

fun kindName(k: GbBank) = when (k) {
    GbBank.PULSE -> "pulse"
    GbBank.WAVE -> "wave"
    GbBank.NOISE -> "noise"
}

/** Generated GB wave shapes: real 32-sample, 4-bit tables. */
object WaveShapes {
    const val SAMPLES = 32
    const val MAX = 15

    fun sine(): List<Int> = List(SAMPLES) { i ->
        (7.5 + 7.5 * sin(2.0 * PI * i / SAMPLES)).roundToInt().coerceIn(0, MAX)
    }

    fun triangle(): List<Int> = List(SAMPLES) { i -> if (i < 16) i else 31 - i }

    fun saw(): List<Int> = List(SAMPLES) { i -> i / 2 }

    fun square(): List<Int> = List(SAMPLES) { i -> if (i < 16) MAX else 0 }

    fun invert(w: List<Int>): List<Int> = w.map { MAX - it }

    fun rotate(w: List<Int>, by: Int): List<Int> = List(w.size) { i -> w[(i - by).mod(w.size)] }

    /** Three-tap circular smoothing, rounded. */
    fun smooth(w: List<Int>): List<Int> = List(w.size) { i ->
        val a = w[(i - 1).mod(w.size)]
        val b = w[i]
        val c = w[(i + 1) % w.size]
        ((a + 2 * b + c) / 4.0).roundToInt().coerceIn(0, MAX)
    }
}
