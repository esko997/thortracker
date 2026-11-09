package org.thortracker.system.opn

import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmPresets
import org.thortracker.system.ym2612.YmSweep

/**
 * Starting sounds for OPN-family songs. A new song has them in its slots: the FM voices (the Mega Drive's FM presets:
 * the same OPN voice; [fmFor] clears the LFO depths on the YM2203, which has no LFO), the SSG sounds and, on the
 * YM2608, one rhythm sound per drum and one ADPCM sound per [OpnSampleKit] starter sample. The instrument editor's
 * Preset control loads any of them into a slot.
 */
object OpnPresets {
    /** The Mega Drive's FM voice presets (categories FM and DRUM, with their voicing notes). */
    val FM: List<YmPresets.Preset> = YmPresets.ALL.filter { it.instrument is YmFmInstrument }

    /** [ym] as an OPN voice for [chip]: on the YM2203 without AMS, FMS and operator AM (it has no LFO). */
    fun fmFor(chip: OpnChip, ym: YmFmInstrument): OpnFmSound {
        val voice = OpnFmSound.of(ym)
        return if (chip.opna) voice else voice.copy(ams = 0, fms = 0, operators = voice.operators.map { it.copy(am = false) })
    }

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    val SSG: List<OpnSsgSound> = listOf(
        OpnSsgSound("SSG lead", volume = env(15, 14, 13, 12, 12, 9, 6, 3, 0, loop = 4, release = 5)),
        OpnSsgSound("SSG pluck", volume = env(15, 13, 11, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 0)),
        OpnSsgSound("SSG arp major", volume = env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7),
            arpeggio = env(0, 0, 4, 4, 7, 7, loop = 0)),
        OpnSsgSound("SSG arp minor", volume = env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7),
            arpeggio = env(0, 0, 3, 3, 7, 7, loop = 0)),
        // The hardware envelope's falling saw at the note's pitch (tone period >> 4) gates the square: a buzzy bass.
        OpnSsgSound("SSG buzz bass", volume = env(15, loop = 0),
            envelope = S5bEnvelope.EPSM_DEFAULT.copy(shape = S5bShape.SAW_DOWN, autoPitch = true, octave = 4)),
        // A slow manual triangle swells the square in and out: a tremolo pad.
        OpnSsgSound("SSG swell pad", volume = env(15, loop = 0),
            envelope = S5bEnvelope.EPSM_DEFAULT.copy(shape = S5bShape.TRIANGLE, autoPitch = false, period = 1800)),
        // A falling square for the body, then noise.
        OpnSsgSound("SSG kick", volume = env(15, 14, 12, 10, 8, 6, 4, 2, 1, 0), sweep = YmSweep(start = 12, end = -24, speed = 40)),
        OpnSsgSound("SSG snare", volume = env(15, 13, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0),
            mixer = env(0, 0, 1, loop = 2), noise = env(6, 8, 10, loop = 2)),
        OpnSsgSound("SSG hat", volume = env(12, 8, 5, 3, 1, 0), mixer = env(1, loop = 0), noise = env(1, loop = 0)),
    )

    /** One rhythm sound per drum, in register `$10` bit order (any rhythm sound plays on any rhythm channel). */
    val RHYTHM: List<OpnRhythmSound> = listOf(
        OpnRhythmSound("Kick", level = 31),
        OpnRhythmSound("Snare", level = 29),
        OpnRhythmSound("Cymbal", level = 25),
        OpnRhythmSound("Hi-hat", level = 24),
        OpnRhythmSound("Tom", level = 28),
        OpnRhythmSound("Rim", level = 26),
    )

    /** An ADPCM sound playing song sample [sample] at its recorded rate on [OpnSampleKit.ROOT] (C-4). */
    fun adpcm(sample: Int, name: String): OpnAdpcmSound = OpnAdpcmSound(name.take(OpnaSample.MAX_NAME), sample = sample, root = OpnSampleKit.ROOT)

    /**
     * A new song's sound for [chip]: the FM voices, then the SSG sounds and, on the YM2608, the rhythm sounds and one
     * ADPCM sound per starter sample ([OpnSampleKit.KINDS], generated into the song's samples); the other slots empty.
     */
    fun blank(chip: OpnChip): OpnSound {
        val sounds = ArrayList<OpnInstrument>()
        FM.forEach { sounds += fmFor(chip, it.instrument as YmFmInstrument) }
        sounds += SSG
        val samples = if (chip.opna) OpnSampleKit.KINDS.map { OpnSampleKit.generate(it) } else emptyList()
        if (chip.opna) {
            sounds += RHYTHM
            samples.forEachIndexed { i, s -> sounds += adpcm(i, s.name) }
        }
        check(sounds.size <= OpnSound.SLOTS) { "${sounds.size} preset sounds do not fit ${OpnSound.SLOTS} slots" }
        return OpnSound(
            chip = chip,
            instruments = List(OpnSound.SLOTS) { sounds.getOrNull(it) },
            samples = samples,
            rhythmLevel = if (chip.opna) 63 else 0,
        )
    }

    /** The sound a slot of [part] starts with when the editor creates one: the part's first preset. */
    fun default(chip: OpnChip, part: OpnPart): OpnInstrument = when (part) {
        OpnPart.FM -> fmFor(chip, FM.first().instrument as YmFmInstrument)
        OpnPart.SSG -> SSG.first()
        OpnPart.RHYTHM -> RHYTHM.first()
        OpnPart.ADPCM -> adpcm(0, "ADPCM")
    }
}
