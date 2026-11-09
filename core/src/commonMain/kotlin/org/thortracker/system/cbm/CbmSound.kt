package org.thortracker.system.cbm

import kotlinx.serialization.Serializable
import org.thortracker.backend.CbmImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * The two Commodore sound chips Thor Tracker's own player drives: the VIC-20's 6560/6561 [VIC] (three squares an octave
 * apart, a noise voice, one 4-bit master volume) and the C16/Plus/4's 7360/8360 [TED] (two squares, voice 2 also noise,
 * one volume 0..8 that D/A mode turns into a DAC). [code] is the image's chip byte.
 */
@Serializable
enum class CbmChip(val code: Int, val maxVolume: Int) {
    VIC(CbmImage.CHIP_VIC, CbmImage.VIC_MAX_VOLUME),
    TED(CbmImage.CHIP_TED, CbmImage.TED_MAX_VOLUME);

    /** The system that plays this chip. */
    val system: CbmSystem get() = if (this == VIC) VicSystem else TedSystem

    /** Tracker channels (lanes). */
    val channels: Int get() = if (this == VIC) CbmImage.VIC_CHANNELS else CbmImage.TED_CHANNELS

    /** The highest wave value an envelope step or `Wxx` holds: VIC 1 (on/off), TED 2 (noise). */
    val maxWave: Int get() = if (this == VIC) CbmImage.WAVE_SQUARE else CbmImage.WAVE_NOISE
}

/** The machine's video standard: it sets the chip clock, the frame (one tick per frame × multispeed) and the period table. */
@Serializable
enum class CbmVideo(val code: Int) { PAL(CbmImage.VIDEO_PAL), NTSC(CbmImage.VIDEO_NTSC) }

/** What a sound plays: [TONE] a voice lane's square (or noise), [SAMPLE] a recording through the TED's D/A (TED only). */
@Serializable
enum class CbmKind { TONE, SAMPLE }

/**
 * One instrument envelope: a value per player tick. Playback runs through the values, then jumps back to [loop] (a
 * value index) or holds the last value when [loop] is -1. [release] is where a released note (OFF) continues: while
 * the note is held only `[0, release)` plays (looping to [loop] inside it), after OFF the values from [release] on
 * play once and hold the last. A volume or wave envelope with a release point makes OFF a release instead of a stop.
 * Empty = not used.
 */
@Serializable
data class CbmEnvelope(val values: List<Int> = emptyList(), val loop: Int = -1, val release: Int = -1) {
    val isEmpty: Boolean get() = values.isEmpty()

    /**
     * Why the envelope cannot load with values in [range], or null: at most [MAX_LENGTH] values, [loop] -1 or a value
     * index, [release] -1 or 1..length − 1 and after [loop] when both are set.
     */
    fun problem(range: IntRange): String? {
        val n = values.size
        return when {
            n > MAX_LENGTH -> "has $n steps; envelopes have at most $MAX_LENGTH"
            n == 0 && (loop != -1 || release != -1) -> "has a loop or release point but no steps"
            loop != -1 && loop !in 0 until n -> "loop point $loop is outside its $n steps"
            release != -1 && release !in 1 until n -> "release point $release must be 1-${n - 1} (a step after the first)"
            loop != -1 && release != -1 && release <= loop -> "release point $release must come after the loop point $loop"
            else -> values.firstOrNull { it !in range }?.let { "step value $it is outside ${range.first}..${range.last}" }
        }
    }

    companion object {
        /** The player's longest envelope (an u8 length). */
        const val MAX_LENGTH = CbmImage.MAX_ENVELOPE

        /** Arpeggio steps: semitones added to the note (VIC noise: rate steps). */
        val ARPEGGIO = -64..63

        /** Pitch steps: 1/64 semitone (absolute per tick, or accumulated when [CbmInstrument.pitchRelative]). */
        val PITCH = -128..127

        val NONE = CbmEnvelope()
    }
}

/**
 * A VIC-20 or C16/Plus/4 sound: [kind], and four envelopes. [volume] shapes the chip's **one master volume** (0..15
 * VIC, 0..8 TED): a note of a sound with one takes the master volume for every voice while it sounds (the lane tags
 * show which); [arpeggio] adds semitones, [pitch] 1/64 semitones (absolute per tick, or summed when [pitchRelative]);
 * [waves] picks the wave per tick (0 off, 1 square, 2 noise on the TED; on the VIC 0 gates the voice off, 1 on).
 *
 * - [CbmKind.TONE] plays [wave] (1 square; on the TED 2 = noise, voice 2 only) at the note's pitch on a voice lane;
 *   on the VIC's Noise lane every tone sound plays noise, the note picking the rate.
 * - [CbmKind.SAMPLE] (TED only, DAC lane only) plays sample [sample] of [CbmSound.samples] through the D/A: note
 *   [root] plays one frame per timer interrupt (the song's DAC rate); [loop] repeats from [loopStart]. Sample sounds
 *   have no volume and wave envelopes (the DAC ignores the master volume).
 *
 * Fields of the other kind are kept (switching back restores them) but not lowered.
 */
@Serializable
data class CbmInstrument(
    val name: String = "Sound",
    val kind: CbmKind = CbmKind.TONE,
    val wave: Int = CbmImage.WAVE_SQUARE,
    val volume: CbmEnvelope = CbmEnvelope(),
    val arpeggio: CbmEnvelope = CbmEnvelope(),
    val pitch: CbmEnvelope = CbmEnvelope(),
    val pitchRelative: Boolean = false,
    val waves: CbmEnvelope = CbmEnvelope(),
    val sample: Int = 0,
    val root: Int = ROOT,
    val loop: Boolean = false,
    val loopStart: Int = 0,
) {
    /** True when a tone sound can play noise (its wave or a wave envelope step is noise): TED voice 2 only. */
    val playsNoise: Boolean
        get() = kind == CbmKind.TONE && (wave == CbmImage.WAVE_NOISE || waves.values.any { it == CbmImage.WAVE_NOISE })

    /** True when OFF releases instead of stopping: the volume or the wave envelope has a release point. */
    val releases: Boolean get() = volume.release >= 0 || waves.release >= 0

    /**
     * Why a field is out of its own range on [chip], or null (the sample reference is checked by
     * [CbmValidator.instrumentProblem], which knows the song).
     */
    fun problem(chip: CbmChip): String? {
        if (kind == CbmKind.SAMPLE && chip == CbmChip.VIC) return "is a sample sound; the VIC-20 has no D/A, so its sounds are tones"
        volume.problem(0..chip.maxVolume)?.let { return "master volume envelope $it" }
        arpeggio.problem(CbmEnvelope.ARPEGGIO)?.let { return "arpeggio envelope $it" }
        pitch.problem(CbmEnvelope.PITCH)?.let { return "pitch envelope $it" }
        waves.problem(CbmImage.WAVE_OFF..chip.maxWave)?.let { return "wave envelope $it" }
        return when {
            wave !in CbmImage.WAVE_SQUARE..chip.maxWave ->
                if (chip == CbmChip.VIC) "wave $wave: VIC sounds play the square (1); the Noise lane makes any sound noise"
                else "wave $wave is outside 1 (square) and 2 (noise)"
            sample !in 0 until CbmSound.MAX_SAMPLES -> "sample $sample is outside 0-${CbmSound.MAX_SAMPLES - 1}"
            root !in CbmImage.ROOT_MIN..CbmImage.ROOT_MAX -> "root note $root is outside ${CbmImage.ROOT_MIN}-${CbmImage.ROOT_MAX} (C-2..C-9)"
            loopStart !in 0 until CbmSample.MAX_FRAMES -> "loop start $loopStart is outside 0-${CbmSample.MAX_FRAMES - 1}"
            else -> null
        }
    }

    companion object {
        /** The root note a new sample sound plays its sample at its own rate on: C-4. */
        const val ROOT = 48

        val DEFAULT = CbmInstrument()
    }
}

/** One TED DAC sample: [data] = frames 0..[CbmImage.DAC_MAX] (4 is the centre), one per timer interrupt at the root note. */
@Serializable
data class CbmSample(val name: String, val data: List<Int>) {
    /** Why the sample cannot load, or null. */
    fun problem(): String? = when {
        data.isEmpty() -> "has no frames"
        data.size > MAX_FRAMES -> "has ${data.size} frames; samples have at most $MAX_FRAMES"
        else -> data.firstOrNull { it !in 0..CbmImage.DAC_MAX }?.let { "frame value $it is outside 0-${CbmImage.DAC_MAX}" }
    }

    companion object {
        const val MAX_FRAMES = CbmImage.MAX_SAMPLE_FRAMES

        /** Longest sample name. */
        const val MAX_NAME = 16
    }
}

/**
 * A VIC-20 or C16/Plus/4 song's chip data: the [chip] (which system plays it), exactly [CbmSystem.SLOTS] instrument
 * slots (slot n = index n − 1; null = empty), the TED's DAC [samples] (none on the VIC), the machine's [video]
 * standard, [multispeed] 1..4 player ticks per frame, and the chip's shared state at the song start: the master
 * [volume] (0..15 VIC, 0..8 TED; `Vxx`, `Axy`) and the TED's [dacTimer] T (timer 1 reload 64 + 4 T single clocks,
 * one DAC frame per interrupt; `Txx`; 0 on the VIC).
 */
@Serializable
data class CbmSound(
    val chip: CbmChip,
    val instruments: List<CbmInstrument?>,
    val samples: List<CbmSample> = emptyList(),
    val video: CbmVideo = CbmVideo.PAL,
    val multispeed: Int = 1,
    val volume: Int = chip.maxVolume,
    val dacTimer: Int = if (chip == CbmChip.TED) DEFAULT_TIMER else 0,
) : SystemSound {
    override val system: SoundSystem get() = chip.system

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): CbmInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: CbmInstrument?): CbmSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Why a song-level value is out of range, or null. */
    fun problem(): String? {
        if (instruments.size != CbmSystem.SLOTS) return "has ${instruments.size} sound slots; ${chip.system.name} songs have ${CbmSystem.SLOTS}"
        if (samples.size > MAX_SAMPLES) return "has ${samples.size} samples; the player holds at most $MAX_SAMPLES"
        if (chip == CbmChip.VIC && samples.isNotEmpty()) return "has ${samples.size} samples; the VIC-20 has no D/A to play them"
        samples.forEachIndexed { i, s -> s.problem()?.let { return "sample ${hex2(i)} \"${s.name}\" $it" } }
        return when {
            multispeed !in 1..MAX_MULTISPEED -> "multispeed $multispeed is outside 1-$MAX_MULTISPEED"
            volume !in 0..chip.maxVolume -> "master volume $volume is outside 0-${chip.maxVolume}"
            dacTimer !in 0..MAX_TIMER -> "DAC timer $dacTimer is outside 0-$MAX_TIMER"
            chip == CbmChip.VIC && dacTimer != 0 -> "DAC timer $dacTimer: the VIC-20 has no DAC timer (0)"
            else -> null
        }
    }

    companion object {
        const val MAX_SAMPLES = CbmImage.MAX_SAMPLES
        const val MAX_MULTISPEED = CbmImage.MAX_MULTISPEED
        const val MAX_TIMER = 255

        /** A new TED song's DAC timer: 64 + 4 · 12 = 112 single clocks, 7917 Hz on a PAL machine. */
        const val DEFAULT_TIMER = 12

        /** A new [chip] song's sound: the chip's presets in slots 01.. ([CbmPresets.forChip]), PAL, full volume. */
        fun blank(chip: CbmChip): CbmSound {
            val presets = CbmPresets.forChip(chip).map { it.instrument }
            return CbmSound(chip, List(CbmSystem.SLOTS) { presets.getOrNull(it) })
        }

        private fun hex2(v: Int) = org.thortracker.model.NumberText.hex(v, 2)
    }
}

/** The VIC-20 / C16-Plus/4 data of a song of either system. */
val Song.cbm: CbmSound get() = sound as CbmSound
