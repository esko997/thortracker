package org.thortracker.system.nes

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song

/** NES document edits: the one instrument bank and its envelopes, as undoable [EditorSession] commands. */

/**
 * The four envelopes of a [NesInstrument], with the values each holds ([NesEnvelope] ranges; duty depends on the
 * sound's [NesChip]) and the value a new step starts from. Only volume has a release point (FamiStudio's rule).
 */
enum class NesEnvelopeKind(val label: String, val range: IntRange, val fallback: Int) {
    VOLUME("Volume", NesEnvelope.VOLUME, 15),
    ARPEGGIO("Arpeggio", NesEnvelope.ARPEGGIO, 0),
    PITCH("Pitch", NesEnvelope.PITCH, 0),
    DUTY("Duty", NesEnvelope.DUTY, 0);

    val canRelease: Boolean get() = this == VOLUME

    /** The values this envelope holds on a [chip] sound: the VRC6 has eight duties. */
    fun range(chip: NesChip): IntRange = if (this == DUTY) chip.duty else range

    fun of(i: NesInstrument): NesEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        DUTY -> i.duty
    }

    fun set(i: NesInstrument, e: NesEnvelope): NesInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        DUTY -> i.copy(duty = e)
    }

    /**
     * [e] made legal for this envelope of a [chip] sound: values clamped, loop < length, and a release only on volume
     * and only after a loop (loop < release < length; FamiStudio drops a release without a loop). A VRC7 or N163 sound's
     * duty envelope is always empty (their channels have no duty).
     */
    fun fixed(e: NesEnvelope, chip: NesChip = NesChip.APU): NesEnvelope {
        if (this == DUTY && (chip == NesChip.VRC7 || chip == NesChip.N163)) {
            return if (e.values.isEmpty() && e.loop < 0 && e.release < 0) e else NesEnvelope()
        }
        val range = range(chip)
        val values = if (e.values.all { it in range }) e.values else e.values.map { it.coerceIn(range) }
        val last = values.size - 1
        var release = if (canRelease && e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        if (loop < 0) release = -1
        val out = NesEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [NesEnvelopeKind.fixed]. A step index of `size` appends. */
object NesEnvelopes {
    /** [e] with step [step] set to [value]; [step] == size appends (up to [NesEnvelope.MAX_LENGTH]). */
    fun withValue(kind: NesEnvelopeKind, e: NesEnvelope, step: Int, value: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        val v = value.coerceIn(kind.range(chip))
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < NesEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values), chip)
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else the default). */
    fun valueAt(kind: NesEnvelopeKind, e: NesEnvelope, step: Int): Int =
        e.values.getOrNull(step) ?: e.values.lastOrNull() ?: kind.fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: NesEnvelopeKind, e: NesEnvelope, step: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(NesEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)), chip)
    }

    /** [e] cut or extended (repeating its last value) to [length] steps; a loop past the end moves to the last step. */
    fun withLength(kind: NesEnvelopeKind, e: NesEnvelope, length: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        val n = length.coerceIn(0, NesEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(kind, e, e.values.size) }
        return kind.fixed(NesEnvelope(values, e.loop.coerceAtMost(n - 1), e.release), chip)
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: NesEnvelopeKind, e: NesEnvelope, loop: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)), chip)
    }

    /**
     * [e] releasing at [release] (-1: none), after the loop point. A release needs a loop: setting one on an envelope
     * without a loop loops it from step 0. Moving below the first legal step removes the release.
     */
    fun withRelease(kind: NesEnvelopeKind, e: NesEnvelope, release: Int): NesEnvelope {
        if (!kind.canRelease || e.values.size < 2) return kind.fixed(e.copy(release = -1))
        val loop = maxOf(e.loop, 0)
        val first = loop + 1
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(if (r < 0) e.copy(release = -1) else e.copy(loop = loop, release = r))
    }
}

/** Instrument [slot] (1..[NesSystem.SLOTS]), or null. */
fun Song.nesInstrument(slot: Int): NesInstrument? = nes.instruments.getOrNull(slot - 1)

/** A new instrument for [slot], as a blank song has it. */
fun defaultNesInstrument(slot: Int) = NesInstrument(name = "Sound ${hex2(slot)}")

/**
 * Sound [slot] made a [chip] sound: duty values clamped to that chip's (VRC7 and N163 sounds have none), the saw volume,
 * the FM patch and the N163 waves back to their defaults when it leaves the VRC6 / VRC7 / N163. Refused (with a note)
 * when the chip is not the song's or the sound still maps DPCM samples, which only 2A03 sounds play.
 */
fun EditorSession.setNesChip(slot: Int, chip: NesChip): Boolean {
    val ins = song.nesInstrument(slot) ?: return false
    if (ins.chip == chip) return false
    if (chip !in song.nes.expansion.chips) { note("This song has no ${chip.label} chip"); return false }
    if (chip != NesChip.APU && ins.dpcm.isNotEmpty()) {
        note("Sound ${hex2(slot)} maps DPCM samples, which only 2A03 sounds play; clear its keys first")
        return false
    }
    return setNesInstrument(slot, ins.forChip(chip), "Make sound ${chip.label}")
}

/**
 * This sound as a [chip] sound: its envelopes kept (duty clamped, none on the VRC7 and the N163), other chips' settings
 * reset. A sound becoming an N163 sound starts from FamiStudio's default wave (a 16-sample sine at RAM 0, legal with any
 * channel count).
 */
fun NesInstrument.forChip(chip: NesChip): NesInstrument = copy(
    chip = chip,
    duty = NesEnvelopeKind.DUTY.fixed(duty, chip),
    sawVolume = if (chip == NesChip.VRC6) sawVolume else Vrc6SawVolume.HALF,
    fm = if (chip == NesChip.VRC7) fm else Vrc7Patch.DEFAULT,
    wave = if (chip == NesChip.N163) wave else N163Wave.DEFAULT,
)

/**
 * [instrument] as [song] can hold it: a sound of a chip the song lacks becomes a 2A03 sound (pasted across songs); an
 * N163 sound's waves are fitted into the song's N163 RAM ([N163Waves.fixed]: songs with more channels have less).
 */
fun nesInstrumentFor(song: Song, instrument: NesInstrument): NesInstrument = when {
    instrument.chip !in song.nes.expansion.chips -> instrument.forChip(NesChip.APU)
    instrument.chip == NesChip.N163 -> N163Waves.fixed(instrument.wave, N163Waves.ram(song)).let {
        if (it === instrument.wave) instrument else instrument.copy(wave = it)
    }
    else -> instrument
}

/**
 * N163 wave edits, following FamiStudio 4.5.3's rules (`Instrument.N163WaveSize`, `N163WavePos`, `N163WaveCount`, the
 * `WaveformRepeat` envelope): sizes are multiples of 4 from 4, positions even, a wave lies inside the song's wave RAM
 * ([ram] samples, [NesRules.n163WaveRam]), at most [N163Wave.maxWaves] waves, repeats 1..15 frames, and the sequence's
 * loop and release follow the volume envelope's rules (a release needs a loop, loop < release < count). Every edit
 * returns a wave [fixed] keeps legal.
 */
object N163Waves {
    /** The level the N163 plays as silence (its DAC's centre): what a cleared sample becomes. */
    const val CENTRE = 8

    /** Wave RAM in samples of [song]'s N163: the 256 samples less 16 per enabled channel's registers. */
    fun ram(song: Song): Int = NesRules.n163WaveRam(song.nes.expansion.n163Channels)

    /** The largest wave that fits at [position] in [ram] samples: what is left after it, a multiple of 4 (at least 4). */
    fun maxSize(ram: Int, position: Int): Int = maxOf(N163Wave.MIN_SIZE, (ram - position) and 3.inv())

    /** The furthest a wave of [size] samples may start in [ram] samples (even). */
    fun maxPosition(ram: Int, size: Int): Int = maxOf(0, ram - size) and 1.inv()

    /**
     * [samples] stretched or squeezed to [size] samples: each new sample takes the old sample nearest its centre, so
     * doubling repeats every sample and halving keeps every second one.
     */
    fun resampled(samples: List<Int>, size: Int): List<Int> {
        if (samples.size == size) return samples
        if (samples.isEmpty()) return List(size) { CENTRE }
        val n = samples.size
        return List(size) { i -> samples[((2 * i + 1) * n / (2 * size)).coerceIn(0, n - 1)] }
    }

    /**
     * [w] made legal for [ram] samples of wave RAM: size a multiple of 4 that fits, position even and inside the RAM
     * with the whole wave, 1..[N163Wave.maxWaves] waves of exactly size samples 0..15, one repeat (1..15) per wave and
     * the volume envelope's loop and release rules on the sequence. Returns [w] itself when it already is.
     */
    fun fixed(w: N163Wave, ram: Int): N163Wave {
        val size = (w.size and 3.inv()).coerceIn(N163Wave.MIN_SIZE, maxOf(N163Wave.MIN_SIZE, ram and 3.inv()))
        val position = (w.position and 1.inv()).coerceIn(0, maxPosition(ram, size))
        val count = w.waves.size.coerceIn(1, N163Wave.maxWaves(size))
        val waves = if (w.waves.isEmpty()) listOf(N163Presets.sine(size)) else w.waves.take(count).map { wave ->
            val s = resampled(wave, size)
            if (s.all { it in N163Wave.SAMPLE }) s else s.map { it.coerceIn(N163Wave.SAMPLE) }
        }
        val repeat = List(count) { (w.repeat.getOrNull(it) ?: N163Wave.REPEAT.first).coerceIn(N163Wave.REPEAT) }
        val seq = NesEnvelopeKind.VOLUME.fixed(NesEnvelope(repeat, w.loop, w.release))
        val out = N163Wave(size, position, waves, repeat, seq.loop, seq.release)
        return if (out == w) w else out
    }

    /**
     * [w] with [size] samples per wave, clamped to 4..[maxSize] at its position (multiples of 4). Waves made from a
     * preset are made again from it at the new size; drawn waves are [resampled]; waves beyond [N163Wave.maxWaves] of
     * the new size are dropped from the end.
     */
    fun withSize(w: N163Wave, size: Int, ram: Int): N163Wave {
        val s = (size and 3.inv()).coerceIn(N163Wave.MIN_SIZE, maxSize(ram, w.position))
        if (s == w.size) return fixed(w, ram)
        val count = minOf(w.waves.size, N163Wave.maxWaves(s))
        val preset = N163Presets.presetOf(w.waves, w.size)
        val waves = if (preset != null) N163Presets.waves(preset, s, count) else w.waves.take(count).map { resampled(it, s) }
        return fixed(w.copy(size = s, waves = waves, repeat = w.repeat.take(count)), ram)
    }

    /** [w] starting at sample [position] of the RAM: even, from 0 to where the whole wave still fits. */
    fun withPosition(w: N163Wave, position: Int, ram: Int): N163Wave =
        fixed(w.copy(position = (position and 1.inv()).coerceIn(0, maxPosition(ram, w.size))), ram)

    /** [w] with sample [index] of wave [wave] set to [value] (0..15). */
    fun withSample(w: N163Wave, wave: Int, index: Int, value: Int, ram: Int): N163Wave {
        val samples = w.waves.getOrNull(wave) ?: return w
        if (index !in samples.indices) return w
        return fixed(w.copy(waves = w.waves.replaced(wave, samples.replaced(index, value.coerceIn(N163Wave.SAMPLE)))), ram)
    }

    /** [w] with wave [wave] replaced by [samples], resampled to the wave size (a pasted wave). */
    fun withWave(w: N163Wave, wave: Int, samples: List<Int>, ram: Int): N163Wave {
        if (wave !in w.waves.indices) return w
        return fixed(w.copy(waves = w.waves.replaced(wave, resampled(samples, w.size))), ram)
    }

    /** [w] with [count] waves (1..[N163Wave.maxWaves]): cut from the end, or extended with copies of the last wave. */
    fun withCount(w: N163Wave, count: Int, ram: Int): N163Wave {
        val n = count.coerceIn(1, N163Wave.maxWaves(w.size))
        val cur = w.waves.size
        if (n == cur) return fixed(w, ram)
        val waves = if (n < cur) w.waves.take(n) else w.waves + List(n - cur) { w.waves.last() }
        val repeat = if (n < cur) w.repeat.take(n) else w.repeat + List(n - cur) { w.repeat.lastOrNull() ?: N163Wave.REPEAT.first }
        return fixed(w.copy(waves = waves, repeat = repeat), ram)
    }

    /** True when [w] holds as many waves as its size allows. */
    fun full(w: N163Wave): Boolean = w.waves.size >= N163Wave.maxWaves(w.size)

    /**
     * [w] with a copy of wave [wave] (and its repeat) inserted after it; the loop and release keep marking the same
     * waves. Unchanged when the sequence is [full].
     */
    fun duplicated(w: N163Wave, wave: Int, ram: Int): N163Wave {
        if (wave !in w.waves.indices || full(w)) return w
        fun shift(point: Int) = if (point > wave) point + 1 else point
        val repeat = List(w.waves.size) { w.repeat.getOrNull(it) ?: N163Wave.REPEAT.first }
        val waves = w.waves.toMutableList().also { it.add(wave + 1, w.waves[wave]) }
        val repeats = repeat.toMutableList().also { it.add(wave + 1, repeat[wave]) }
        return fixed(w.copy(waves = waves, repeat = repeats, loop = shift(w.loop), release = shift(w.release)), ram)
    }

    /** [w] without wave [wave] (the last remaining wave stays); loop and release move as on a volume envelope. */
    fun removed(w: N163Wave, wave: Int, ram: Int): N163Wave {
        if (wave !in w.waves.indices || w.waves.size == 1) return w
        val seq = NesEnvelopes.removed(NesEnvelopeKind.VOLUME, sequence(w), wave)
        return fixed(w.copy(waves = w.waves.toMutableList().also { it.removeAt(wave) }).withSequence(seq), ram)
    }

    /** [w] playing wave [wave] for [frames] frames (1..15). */
    fun withRepeat(w: N163Wave, wave: Int, frames: Int, ram: Int): N163Wave {
        if (wave !in w.waves.indices) return w
        return fixed(w.copy(repeat = sequence(w).values.replaced(wave, frames.coerceIn(N163Wave.REPEAT))), ram)
    }

    /** [w] looping back to wave [loop] after its last wave (-1: the last wave holds); kept before the release. */
    fun withLoop(w: N163Wave, loop: Int, ram: Int): N163Wave =
        fixed(w.withSequence(NesEnvelopes.withLoop(NesEnvelopeKind.VOLUME, sequence(w), loop)), ram)

    /**
     * [w] releasing at wave [release] (-1: none), as a volume envelope releases: a release needs a loop (setting one
     * without a loop loops from wave 0), loop < release < count.
     */
    fun withRelease(w: N163Wave, release: Int, ram: Int): N163Wave =
        fixed(w.withSequence(NesEnvelopes.withRelease(NesEnvelopeKind.VOLUME, sequence(w), release)), ram)

    /** Every wave of [w] made from [preset] (FamiStudio's values at the wave size; sweeping presets across all waves). */
    fun withPreset(w: N163Wave, preset: N163Preset, ram: Int): N163Wave =
        fixed(w.copy(waves = N163Presets.waves(preset, w.size, w.waves.size)), ram)

    /** The sequence as a volume-style envelope: one repeat per wave, the loop and the release. */
    private fun sequence(w: N163Wave) =
        NesEnvelope(List(w.waves.size) { w.repeat.getOrNull(it) ?: N163Wave.REPEAT.first }, w.loop, w.release)

    private fun N163Wave.withSequence(e: NesEnvelope) = copy(repeat = e.values, loop = e.loop, release = e.release)
}

/** Edits the waves of N163 sound [slot], kept legal for the song's N163 RAM ([N163Waves.fixed]). */
fun EditorSession.editNesWave(slot: Int, label: String, f: (N163Wave) -> N163Wave): Boolean {
    val ram = N163Waves.ram(song)
    return editNesInstrument(slot, label) { if (it.chip == NesChip.N163) it.copy(wave = N163Waves.fixed(f(it.wave), ram)) else it }
}

/** Edits the FM patch of VRC7 sound [slot]. */
fun EditorSession.editNesFm(slot: Int, label: String, f: (Vrc7Patch) -> Vrc7Patch): Boolean =
    editNesInstrument(slot, label) { if (it.chip == NesChip.VRC7) it.copy(fm = f(it.fm)) else it }

/** Edits the VRC7 song's release/stop sustain overrides, which every VRC7 sound plays with ([Vrc7Sustain]). */
fun EditorSession.editVrc7Sustain(label: String, f: (Vrc7Sustain) -> Vrc7Sustain): Boolean {
    if (song.nes.expansion != NesExpansion.VRC7) return false
    val next = f(song.nes.vrc7Sustain)
    if (next == song.nes.vrc7Sustain) return false
    return apply(label) { s -> s.copy(sound = s.nes.copy(vrc7Sustain = next)) }
}

/** A copied NES instrument, one of its envelopes, or one N163 wave's samples. */
sealed interface NesClip : Clip {
    data class Sound(val instrument: NesInstrument) : NesClip
    data class Envelope(val kind: NesEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : NesClip
    data class Wave(val samples: List<Int>) : NesClip
}

fun EditorSession.setNesInstrument(slot: Int, instrument: NesInstrument, label: String = "Edit ${instrument.name.ifBlank { "sound" }}"): Boolean {
    if (slot !in 1..song.nes.instruments.size) return false
    return apply(label) { s -> s.copy(sound = s.nes.copy(instruments = s.nes.instruments.replaced(slot - 1, instrument))) }
}

fun EditorSession.editNesInstrument(slot: Int, label: String, f: (NesInstrument) -> NesInstrument): Boolean {
    val cur = song.nesInstrument(slot) ?: return false
    return setNesInstrument(slot, f(cur), label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal for the sound's chip ([NesEnvelopeKind.fixed]). */
fun EditorSession.editNesEnvelope(slot: Int, kind: NesEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editNesInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)), it.chip)) }

fun EditorSession.copyNesInstrument(slot: Int) {
    val i = song.nesInstrument(slot) ?: return
    hold(NesClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyNesEnvelope(slot: Int, kind: NesEnvelopeKind) {
    val i = song.nesInstrument(slot) ?: return
    hold(NesClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/** Copies wave [wave] of N163 sound [slot]. */
fun EditorSession.copyNesWave(slot: Int, wave: Int) {
    val samples = song.nesInstrument(slot)?.takeIf { it.chip == NesChip.N163 }?.wave?.waves?.getOrNull(wave) ?: return
    hold(NesClip.Wave(samples))
    note("Copied wave ${wave + 1} of sound ${hex2(slot)} (${samples.size} samples)")
}

/**
 * Pastes a copied wave over wave [wave] of N163 sound [slot], resampled to its size; a copied sound or envelope pastes
 * as [pasteNes] does.
 */
fun EditorSession.pasteNesWave(slot: Int, wave: Int, kind: NesEnvelopeKind): Boolean {
    val clip = clipboard as? NesClip.Wave ?: return pasteNes(slot, kind)
    val ram = N163Waves.ram(song)
    val ok = editNesWave(slot, "Paste wave") { N163Waves.withWave(it, wave, clip.samples, ram) }
    note("Pasted into wave ${wave + 1}")
    return ok
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it; an envelope goes into [kind] (values clamped to its range,
 * the release kept only on volume), so a shape can move between envelopes.
 */
fun EditorSession.pasteNes(slot: Int, kind: NesEnvelopeKind): Boolean {
    when (val clip = clipboard) {
        is NesClip.Sound -> {
            val ok = setNesInstrument(slot, nesInstrumentFor(song, clip.instrument), "Paste sound")
            note("Pasted into sound ${hex2(slot)}")
            return ok
        }
        is NesClip.Envelope -> {
            val ok = editNesInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
                val with = kind.set(it, kind.fixed(clip.envelope, it.chip))
                if (kind == NesEnvelopeKind.PITCH && clip.kind == NesEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
            }
            note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}")
            return ok
        }
        is NesClip.Wave -> { note("A copied wave pastes onto a wave: the Waves page of an N163 sound, Wave or Samples row"); return false }
        else -> { note("Clipboard holds no NES sound or envelope"); return false }
    }
}
