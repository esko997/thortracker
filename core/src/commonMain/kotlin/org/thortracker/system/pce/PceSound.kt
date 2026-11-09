package org.thortracker.system.pce

import kotlinx.serialization.Serializable
import org.thortracker.backend.PcePlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * What a PC Engine sound plays on its channel: [TONE] the channel's 32-step wave, [NOISE] the channel's noise
 * generator (PSG 5 and PSG 6 only: the HuC6280 has noise on its last two channels), [SAMPLE] the channel's direct D/A
 * (DDA) output, fed one 5-bit frame per interrupt of the CPU timer (any channel).
 */
@Serializable
enum class PceKind { TONE, NOISE, SAMPLE }

/**
 * One instrument envelope: a value per player tick. Playback runs through the values, then jumps back to [loop] (a
 * value index) or holds the last value when [loop] is -1. [release] is where a released note (OFF) continues: while
 * the note is held only `[0, release)` plays (looping to [loop] inside it), after OFF the values from [release] on
 * play once and hold the last. A volume envelope with a release point makes OFF a release instead of a stop. Empty =
 * not used (the defaults: volume 31, no arpeggio, no pitch offset, the instrument's own wave).
 */
@Serializable
data class PceEnvelope(val values: List<Int> = emptyList(), val loop: Int = -1, val release: Int = -1) {
    val isEmpty: Boolean get() = values.isEmpty()

    /**
     * Why the envelope cannot load with values in [range], or null: at most [MAX_LENGTH] values, [loop] -1 or a value
     * index, [release] -1 or 1..length − 1 and after [loop] when both are set. A loop or release on an empty envelope
     * is meaningless and refused.
     */
    fun problem(range: IntRange): String? {
        val n = values.size
        return when {
            n > MAX_LENGTH -> "has $n steps; envelopes have at most $MAX_LENGTH"
            n == 0 && (loop != -1 || release != -1) -> "has a loop or release point but no steps"
            loop != -1 && loop !in 0 until n -> "loop point $loop is outside its ${n} steps"
            release != -1 && release !in 1 until n -> "release point $release must be 1-${n - 1} (a step after the first)"
            loop != -1 && release != -1 && release <= loop -> "release point $release must come after the loop point $loop"
            else -> values.firstOrNull { it !in range }?.let { "step value $it is outside ${range.first}..${range.last}" }
        }
    }

    companion object {
        /** The player's longest envelope (an u8 length). */
        const val MAX_LENGTH = PcePlayerImage.MAX_ENVELOPE

        /** Volume steps: 0 silent .. 31 full (1.5 dB per step). */
        val VOLUME = 0..31

        /** Arpeggio steps: semitones added to the note. */
        val ARPEGGIO = -64..63

        /** Pitch steps: 1/64 semitone (absolute per tick, or accumulated when [PceInstrument.pitchRelative]). */
        val PITCH = -128..127

        val NONE = PceEnvelope()
    }
}

/**
 * A PC Engine sound: its [kind] and four envelopes. [volume] (0..31) shapes the channel volume, [arpeggio] adds
 * semitones, [pitch] adds 1/64 semitones (absolute per tick, or summed tick by tick when [pitchRelative]), and on a
 * tone sound [waves] picks the wave per tick (indices into [PceSound.waves]; empty = the fixed [wave]).
 *
 * - [PceKind.TONE] plays wave [wave] (or the [waves] envelope) at the note's pitch.
 * - [PceKind.NOISE] plays the noise generator; the note picks its rate (note mod 32: C-0..G-2 = rates 0..31, repeating
 *   every 32 semitones), [arpeggio] steps it; pitch envelopes and slides do not apply. PSG 5 and PSG 6 only.
 * - [PceKind.SAMPLE] streams sample [sample] of [PceSound.samples] through the D/A: note [root] plays one frame per
 *   timer interrupt (the song's DDA rate), other notes faster or slower; [loop] repeats from frame [loopStart].
 *
 * Fields of other kinds are kept (switching back restores them) but not lowered.
 */
@Serializable
data class PceInstrument(
    val name: String = "Sound",
    val kind: PceKind = PceKind.TONE,
    val wave: Int = 0,
    val volume: PceEnvelope = PceEnvelope(listOf(31)),
    val arpeggio: PceEnvelope = PceEnvelope(),
    val pitch: PceEnvelope = PceEnvelope(),
    val pitchRelative: Boolean = false,
    val waves: PceEnvelope = PceEnvelope(),
    val sample: Int = 0,
    val root: Int = ROOT,
    val loop: Boolean = false,
    val loopStart: Int = 0,
) {
    /**
     * Why a field is out of its own range, or null (references to the song's waves and samples are checked by
     * [PceValidator.instrumentProblem], which knows the song).
     */
    fun problem(): String? {
        volume.problem(PceEnvelope.VOLUME)?.let { return "volume envelope $it" }
        arpeggio.problem(PceEnvelope.ARPEGGIO)?.let { return "arpeggio envelope $it" }
        pitch.problem(PceEnvelope.PITCH)?.let { return "pitch envelope $it" }
        waves.problem(0 until PceSound.MAX_WAVES)?.let { return "wave envelope $it" }
        return when {
            wave !in 0 until PceSound.MAX_WAVES -> "wave $wave is outside 0-${PceSound.MAX_WAVES - 1}"
            sample !in 0 until PceSound.MAX_SAMPLES -> "sample $sample is outside 0-${PceSound.MAX_SAMPLES - 1}"
            root !in PcePlayerImage.NOTE_MIN..PcePlayerImage.NOTE_MAX -> "root note $root is outside ${PcePlayerImage.NOTE_MIN}-${PcePlayerImage.NOTE_MAX} (A-0..B-7)"
            loopStart !in 0 until PceSample.MAX_FRAMES -> "loop start $loopStart is outside 0-${PceSample.MAX_FRAMES - 1}"
            else -> null
        }
    }

    companion object {
        /** The root note a new sample sound plays its sample at its own rate on: C-4. */
        const val ROOT = 48

        /** A new slot: a flat full-volume tone on wave 0. */
        val DEFAULT = PceInstrument()
    }
}

/**
 * One DDA sample: [data] = 5-bit frames 0..31 (16 is the centre), one per timer interrupt at the root note, 1..
 * [MAX_FRAMES] of them.
 */
@Serializable
data class PceSample(val name: String, val data: List<Int>) {
    /** Why the sample cannot load, or null. */
    fun problem(): String? = when {
        data.isEmpty() -> "has no frames"
        data.size > MAX_FRAMES -> "has ${data.size} frames; samples have at most $MAX_FRAMES"
        else -> data.firstOrNull { it !in 0..PceSound.WAVE_MAX }?.let { "frame value $it is outside 0-${PceSound.WAVE_MAX}" }
    }

    companion object {
        /** Most frames of one sample (the player's 16.16 position and the u16 loop start). */
        const val MAX_FRAMES = PcePlayerImage.MAX_SAMPLE_FRAMES

        /** Longest sample name. */
        const val MAX_NAME = 16
    }
}

/**
 * A PC Engine song's chip data: [instruments] = exactly [PceSystem.SLOTS] slots (slot n = index n − 1; null = empty),
 * the song's [waves] (0..[MAX_WAVES] tables of [WAVE_SIZE] values 0..[WAVE_MAX], in play order), its DDA [samples]
 * (0..[MAX_SAMPLES]), [multispeed] 1..[MAX_MULTISPEED] player ticks per 60 Hz frame, and the chip's **shared** state at
 * the song start — one of each on the HuC6280, so it is song data changed by global effects, not instrument data:
 * the [master] balance (register 1: left << 4 | right, 0..15 each, `Xxy`), the LFO's [lfoFrequency] (register 8,
 * 0 = 256, `Lxx`) and [lfoControl] (register 9: 0 off, 1..3 depth, + $80 halt, `Mxx`; PSG 2's wave modulates PSG 1's
 * pitch), and the CPU timer's [timerReload] (0..[MAX_TIMER]: the DDA rate 6991.3 / (R + 1) Hz, `Txx`).
 */
@Serializable
data class PceSound(
    val instruments: List<PceInstrument?>,
    val waves: List<List<Int>>,
    val samples: List<PceSample> = emptyList(),
    val multispeed: Int = 1,
    val master: Int = 0xFF,
    val lfoFrequency: Int = 0,
    val lfoControl: Int = 0,
    val timerReload: Int = 0,
) : SystemSound {
    override val system: SoundSystem get() = PceSystem

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): PceInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: PceInstrument?): PceSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Wave [index] replaced by [values] (index = [waves].size appends). */
    fun withWave(index: Int, values: List<Int>): PceSound =
        copy(waves = waves.toMutableList().also { if (index == it.size) it += values else it[index] = values })

    /** Sample [index] replaced by [sample] (index = [samples].size appends). */
    fun withSample(index: Int, sample: PceSample): PceSound =
        copy(samples = samples.toMutableList().also { if (index == it.size) it += sample else it[index] = sample })

    /** Master balance left (0..15). */
    val masterLeft: Int get() = master shr 4 and 15

    /** Master balance right (0..15). */
    val masterRight: Int get() = master and 15

    /** Why a song-level value is out of range, or null. */
    fun problem(): String? {
        if (instruments.size != PceSystem.SLOTS) return "has ${instruments.size} sound slots; PC Engine songs have ${PceSystem.SLOTS}"
        if (waves.size > MAX_WAVES) return "has ${waves.size} waves; the player holds at most $MAX_WAVES"
        waves.forEachIndexed { i, w ->
            if (w.size != WAVE_SIZE) return "wave ${hex2(i)} has ${w.size} steps; waves have $WAVE_SIZE"
            w.firstOrNull { it !in 0..WAVE_MAX }?.let { return "wave ${hex2(i)} has the value $it; wave values are 0-$WAVE_MAX" }
        }
        if (samples.size > MAX_SAMPLES) return "has ${samples.size} samples; the player holds at most $MAX_SAMPLES"
        samples.forEachIndexed { i, s -> s.problem()?.let { return "sample ${hex2(i)} \"${s.name}\" $it" } }
        return when {
            multispeed !in 1..MAX_MULTISPEED -> "multispeed $multispeed is outside 1-$MAX_MULTISPEED"
            master !in 0..0xFF -> "master balance $master is outside 00-FF"
            lfoFrequency !in 0..0xFF -> "LFO frequency $lfoFrequency is outside 0-255"
            !PceRules.lfoControlValid(lfoControl) -> "LFO control ${hex2(lfoControl)} is not 00-03 or 80-83"
            timerReload !in 0..MAX_TIMER -> "DDA timer $timerReload is outside 0-$MAX_TIMER"
            else -> null
        }
    }

    companion object {
        const val WAVE_SIZE = PcePlayerImage.WAVE_SIZE
        const val WAVE_MAX = PcePlayerImage.WAVE_MAX
        const val MAX_WAVES = PcePlayerImage.MAX_WAVES
        const val MAX_SAMPLES = PcePlayerImage.MAX_SAMPLES
        const val MAX_MULTISPEED = PcePlayerImage.MAX_MULTISPEED
        const val MAX_TIMER = PcePlayerImage.MAX_TIMER

        /**
         * A new song's sound: the built-in waves ([PcePresets.WAVES]), the tone and noise presets in slots 01..
         * ([PcePresets.ALL], each tone preset on the built-in wave equal to its own), the rest empty; no samples, full
         * master balance, LFO off, DDA at 6991 Hz.
         */
        fun blank(): PceSound {
            val waves = PcePresets.WAVES.map { it.second }
            val instruments = PcePresets.ALL.map { p ->
                val wave = p.wave?.let { w -> waves.indexOf(w) } ?: 0
                p.instrument.copy(wave = maxOf(wave, 0))
            }
            return PceSound(List(PceSystem.SLOTS) { instruments.getOrNull(it) }, waves)
        }

        private fun hex2(v: Int) = org.thortracker.model.NumberText.hex(v, 2)
    }
}

/** The PC Engine data of a HuC6280 song. */
val Song.pce: PceSound get() = sound as PceSound
