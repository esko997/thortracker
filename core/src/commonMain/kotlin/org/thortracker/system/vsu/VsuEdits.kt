package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.model.Songs
import kotlin.math.roundToInt

/** Virtual Boy document edits for the instrument and wave editors: the one bank of [VsuSystem.SLOTS] sounds, the five shared waves and the modulation table. */

/** Instrument [slot] (1..[VsuSystem.SLOTS]), or null when empty or out of range. */
fun Song.vsuInstrument(slot: Int): VsuInstrument? = vsu.instrument(slot)

/** The sound an empty slot gets when it is first edited: the Square lead preset, named after the slot. */
fun defaultVsuInstrument(slot: Int): VsuInstrument = (VsuPresets.named("Square lead")?.instrument ?: VsuPresets.ALL.first().instrument)
    .copy(name = "Sound ${hex2(slot)}")

/** A copied VSU sound, wave or modulation table. */
sealed interface VsuClip : Clip {
    data class Sound(val instrument: VsuInstrument) : VsuClip
    data class Wave(val samples: List<Int>) : VsuClip
    data class Modulation(val values: List<Int>) : VsuClip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setVsuInstrument(slot: Int, instrument: VsuInstrument?, label: String): Boolean {
    if (slot !in 1..VsuSystem.SLOTS) return false
    return apply(label) { s -> if (s.vsu.instrument(slot) == instrument) s else s.copy(sound = s.vsu.withInstrument(slot, instrument)) }
}

/** Edits instrument [slot]; an empty slot starts from [defaultVsuInstrument]. */
fun EditorSession.editVsuInstrument(slot: Int, label: String, f: (VsuInstrument) -> VsuInstrument): Boolean {
    if (slot !in 1..VsuSystem.SLOTS) return false
    val cur = song.vsuInstrument(slot)
    val next = f(cur ?: defaultVsuInstrument(slot))
    return (cur == null || next != cur) && setVsuInstrument(slot, next, label)
}

fun EditorSession.copyVsuInstrument(slot: Int) {
    val i = song.vsuInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(VsuClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.pasteVsuInstrument(slot: Int): Boolean {
    val clip = clipboard as? VsuClip.Sound ?: run { note("Clipboard holds no Virtual Boy sound"); return false }
    return setVsuInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
}

/** Wave [index] (0..4) set to [samples] (32 values, clamped to 0..63). */
fun EditorSession.setVsuWave(index: Int, samples: List<Int>, label: String = "Edit wave"): Boolean {
    if (index !in 0 until VsuPlayerImage.WAVES || samples.size != VsuPlayerImage.WAVE_SAMPLES) return false
    val clamped = samples.map { it.coerceIn(0, VsuPlayerImage.SAMPLE_MAX) }
    return apply(label) { s -> if (s.vsu.waves[index] == clamped) s else s.copy(sound = s.vsu.withWave(index, clamped)) }
}

fun EditorSession.setVsuWaveSample(index: Int, sample: Int, value: Int): Boolean {
    val w = song.vsu.waves.getOrNull(index) ?: return false
    if (sample !in w.indices) return false
    return setVsuWave(index, w.toMutableList().also { it[sample] = value }, "Draw wave")
}

/** The modulation table set to [values] (32 values, clamped to −128..127). */
fun EditorSession.setVsuModulation(values: List<Int>, label: String = "Edit modulation"): Boolean {
    if (values.size != VsuPlayerImage.MOD_VALUES) return false
    val clamped = values.map { it.coerceIn(-128, 127) }
    return apply(label) { s -> if (s.vsu.modulation == clamped) s else s.copy(sound = s.vsu.copy(modulation = clamped)) }
}

fun EditorSession.setVsuModValue(index: Int, value: Int): Boolean {
    val m = song.vsu.modulation
    if (index !in m.indices) return false
    return setVsuModulation(m.toMutableList().also { it[index] = value }, "Draw modulation")
}

/** Copies Wave view page [page]: a wave (0..4) or the modulation table ([VsuWaves.MOD_PAGE]). */
fun EditorSession.copyVsuWavePage(page: Int) {
    if (page == VsuWaves.MOD_PAGE) {
        hold(VsuClip.Modulation(song.vsu.modulation))
        note("Copied the modulation table")
    } else {
        hold(VsuClip.Wave(song.vsu.waves[page]))
        note("Copied wave ${page + 1}")
    }
}

/** Pastes a copied wave or modulation table onto page [page]; a wave and the table do not mix (their ranges differ). */
fun EditorSession.pasteVsuWavePage(page: Int): Boolean = when (val clip = clipboard) {
    is VsuClip.Wave -> if (page == VsuWaves.MOD_PAGE) { note("Clipboard holds a wave; the modulation table takes a copied table"); false }
        else setVsuWave(page, clip.samples, "Paste wave").also { note("Pasted into wave ${page + 1}") }
    is VsuClip.Modulation -> if (page != VsuWaves.MOD_PAGE) { note("Clipboard holds a modulation table; pick the modulation page"); false }
        else setVsuModulation(clip.values, "Paste modulation").also { note("Pasted the modulation table") }
    else -> { note("Clipboard holds no wave or modulation table"); false }
}

/** The shared waves and modulation table: names, users and shape tools. */
object VsuWaves {
    /** The Wave view's page for the modulation table, after the five waves. */
    const val MOD_PAGE = VsuPlayerImage.WAVES
    const val PAGES = VsuPlayerImage.WAVES + 1

    /** The preset name wave [w] of [sound] matches, or null for a drawn wave. */
    fun name(sound: VsuSound, w: Int): String? = sound.waves.getOrNull(w)?.let { s -> VsuPresets.WAVES.firstOrNull { it.samples == s }?.name }

    /** The preset name the modulation table matches, or null. */
    fun modulationName(sound: VsuSound): String? = VsuPresets.MODULATIONS.firstOrNull { it.values == sound.modulation }?.name

    /** Slots whose sound plays wave [w] (on a wave lane). */
    fun soundsUsing(sound: VsuSound, w: Int): List<Int> = sound.instruments.withIndex().filter { it.value?.wave == w }.map { it.index + 1 }

    /** Slots whose sound uses Wave 5's modulation (and so reads the table). */
    fun soundsModulating(sound: VsuSound): List<Int> =
        sound.instruments.withIndex().filter { it.value?.freqMod?.let { f -> f.on && f.modulation } == true }.map { it.index + 1 }

    /** Wave lanes on which the arrangement's notes play wave [w] (from their sound's wave or a `Wxx` lock). */
    fun lanesPlaying(song: Song, w: Int): List<Int> {
        val out = ArrayList<Int>()
        val sound = song.vsu
        for (lane in 0 until VsuRules.NOISE_LANE) {
            var slot = 0
            loop@ for (o in Songs.playedOrders(song)) {
                val pattern = song.patterns[song.orders[o]]
                for (r in 0 until pattern.length) {
                    val c = pattern.tracks[lane][r]
                    if (c.instrument != 0) slot = c.instrument
                    val lock = (0..c.more.size).firstOrNull { c.effectCode(it) == VsuRules.FX_WAVE }?.let { c.effectParam(it) - 1 }
                    val plays = if (c.note >= 0) lock ?: sound.instrument(slot)?.wave else if (c.note == Cell.EMPTY) lock else null
                    if (plays == w) { out += lane; break@loop }
                }
            }
        }
        return out
    }

    fun invert(w: List<Int>): List<Int> = w.map { VsuPlayerImage.SAMPLE_MAX - it }

    fun negate(m: List<Int>): List<Int> = m.map { (-it).coerceIn(-128, 127) }

    fun rotate(w: List<Int>, by: Int): List<Int> = List(w.size) { i -> w[(i - by).mod(w.size)] }

    /** Three-tap circular smoothing, rounded, clamped to [range]. */
    fun smooth(w: List<Int>, range: IntRange): List<Int> = List(w.size) { i ->
        val a = w[(i - 1).mod(w.size)]
        val b = w[i]
        val c = w[(i + 1) % w.size]
        ((a + 2 * b + c) / 4.0).roundToInt().coerceIn(range)
    }
}
