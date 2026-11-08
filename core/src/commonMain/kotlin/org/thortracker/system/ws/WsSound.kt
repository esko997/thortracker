package org.thortracker.system.ws

import kotlinx.serialization.Serializable
import org.thortracker.backend.WsPlayerImage
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * What a WonderSwan sound plays: [TONE] one of the song's 32-step 4-bit waves on CH1-CH4, [NOISE] channel 4's LFSR
 * noise generator (CH4 only), [SAMPLE] 8-bit samples on channel 2's voice output (CH2, mono) or, on the WonderSwan
 * Color, on Hyper Voice (mono or stereo), fed as [WsFeed] says.
 */
@Serializable
enum class WsKind { TONE, NOISE, SAMPLE }

/**
 * How a sample sound's bytes reach the chip: [CPU] the player writes one frame per voice-timer interrupt (12000 / R Hz,
 * pitched from the sound's root note), as original-model drivers do; [DMA] the WonderSwan Color's Sound DMA streams
 * them at a fixed 4000, 6000, 12000 or 24000 bytes per second (the note only triggers), optionally backwards.
 */
@Serializable
enum class WsFeed { CPU, DMA }

/** The console's output a song is heard through (the listener's choice, so a song setting): headphones or the speaker. */
@Serializable
enum class WsOutput { HEADPHONES, SPEAKER }

/**
 * One instrument envelope: a value per player tick. Playback runs through the values, then jumps back to [loop] (a
 * value index) or holds the last value when [loop] is -1. [release] is where a released note (OFF) continues: while the
 * note is held only `[0, release)` plays (looping to [loop] inside it), after OFF the values from [release] on play once
 * and hold the last. A volume envelope with a release point makes OFF a release instead of a stop. Empty = not used
 * (the defaults: volume 15, no arpeggio, no pitch offset, the sound's own wave or tap mode).
 */
@Serializable
data class WsEnvelope(val values: List<Int> = emptyList(), val loop: Int = -1, val release: Int = -1) {
    val isEmpty: Boolean get() = values.isEmpty()

    /**
     * Why the envelope cannot load with values in [range], or null: at most [MAX_LENGTH] values, [loop] -1 or a value
     * index, [release] -1 or 1..length − 1 and after [loop] when both are set; a loop or release on an empty envelope is
     * refused.
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
        /** The player's longest envelope (a u8 length). */
        const val MAX_LENGTH = WsPlayerImage.MAX_ENVELOPE

        /** Volume steps: 0 silent .. 15 full (the chip's 4-bit channel volume per side). */
        val VOLUME = 0..15

        /** Arpeggio steps: semitones added to the note. */
        val ARPEGGIO = -64..63

        /** Pitch steps: 1/64 semitone (absolute per tick, or accumulated when [WsInstrument.pitchRelative]). */
        val PITCH = -128..127

        /** Tap-sequence steps: channel 4's eight LFSR tap modes. */
        val TAPS = 0..WsPlayerImage.MAX_TAP

        val NONE = WsEnvelope()
    }
}

/**
 * A WonderSwan sound: its [kind] and envelopes. [volume] (0..15) shapes the lane's volume, [arpeggio] adds semitones,
 * [pitch] adds 1/64 semitones (absolute per tick, or summed tick by tick when [pitchRelative]).
 *
 * - [WsKind.TONE] plays wave [wave] (or the [waves] sequence) at the note's pitch on CH1-CH4; on CH3 a non-zero
 *   [sweep] (signed, added to the 11-bit frequency register every [sweepTime] + 1 steps of 1/375 s) bends it in
 *   hardware; other lanes ignore the sweep.
 * - [WsKind.NOISE] plays channel 4's LFSR with tap mode [tap] (or the [taps] sequence), clocked at 32 times the note's
 *   tone frequency; [lfsrReset] restarts the LFSR at each note (the same noise every time). CH4 only.
 * - [WsKind.SAMPLE] plays sample [sample] of [WsSound.samples]. [feed] [WsFeed.CPU]: one frame per voice-timer
 *   interrupt on note [root], faster or slower on other notes; [loop] repeats from frame [loopStart]. [WsFeed.DMA]
 *   (WonderSwan Color): Sound DMA at rate [rate] (0..3: 4000, 6000, 12000, 24000 bytes per second) whatever the note,
 *   one-shot or looping the whole sample, [backwards] from its end; arpeggio and pitch do not apply. CH2 plays mono
 *   samples; Hyper Voice (Color) plays mono and stereo ones.
 *
 * Fields of other kinds and the other feed are kept (switching back restores them) but not lowered.
 */
@Serializable
data class WsInstrument(
    val name: String = "Sound",
    val kind: WsKind = WsKind.TONE,
    val wave: Int = 0,
    val tap: Int = 0,
    val sample: Int = 0,
    val feed: WsFeed = WsFeed.CPU,
    val root: Int = ROOT,
    val rate: Int = DEFAULT_RATE,
    val loop: Boolean = false,
    val loopStart: Int = 0,
    val backwards: Boolean = false,
    val sweep: Int = 0,
    val sweepTime: Int = 0,
    val lfsrReset: Boolean = false,
    val volume: WsEnvelope = WsEnvelope(listOf(15)),
    val arpeggio: WsEnvelope = WsEnvelope(),
    val pitch: WsEnvelope = WsEnvelope(),
    val pitchRelative: Boolean = false,
    val waves: WsEnvelope = WsEnvelope(),
    val taps: WsEnvelope = WsEnvelope(),
) {
    /** True for a sample sound fed by Sound DMA (WonderSwan Color only). */
    val isDma: Boolean get() = kind == WsKind.SAMPLE && feed == WsFeed.DMA

    /**
     * Why a field is out of its own range, or null (references to the song's waves and samples, and what the model
     * allows, are checked by [WsValidator.instrumentProblem], which knows the song).
     */
    fun problem(): String? {
        volume.problem(WsEnvelope.VOLUME)?.let { return "volume envelope $it" }
        arpeggio.problem(WsEnvelope.ARPEGGIO)?.let { return "arpeggio envelope $it" }
        pitch.problem(WsEnvelope.PITCH)?.let { return "pitch envelope $it" }
        waves.problem(0 until WsSound.MAX_WAVES)?.let { return "wave envelope $it" }
        taps.problem(WsEnvelope.TAPS)?.let { return "tap envelope $it" }
        return when {
            wave !in 0 until WsSound.MAX_WAVES -> "wave $wave is outside 0-${WsSound.MAX_WAVES - 1}"
            tap !in WsEnvelope.TAPS -> "tap mode $tap is outside 0-${WsPlayerImage.MAX_TAP}"
            sample !in 0 until WsSound.MAX_SAMPLES -> "sample $sample is outside 0-${WsSound.MAX_SAMPLES - 1}"
            root !in WsRules.NOTES -> "root note $root is outside ${WsRules.NOTES.first}-${WsRules.NOTES.last} (G-1..B-7)"
            rate !in 0..WsPlayerImage.MAX_DMA_RATE -> "DMA rate $rate is outside 0-${WsPlayerImage.MAX_DMA_RATE}"
            loopStart !in 0 until WsSample.MAX_FRAMES -> "loop start $loopStart is outside 0-${WsSample.MAX_FRAMES - 1}"
            sweep !in -128..127 -> "sweep $sweep is outside -128..127"
            sweepTime !in 0..WsPlayerImage.MAX_SWEEP_TIME -> "sweep time $sweepTime is outside 0-${WsPlayerImage.MAX_SWEEP_TIME}"
            else -> null
        }
    }

    companion object {
        /** The root note a new CPU-fed sample sound plays its sample at its own rate on: C-4. */
        const val ROOT = 48

        /** A new DMA sound's rate: 6000 bytes per second (rate 1), the starter kit's. */
        const val DEFAULT_RATE = 1

        /** A new slot: a flat full-volume tone on wave 0. */
        val DEFAULT = WsInstrument()
    }
}

/**
 * One 8-bit sample: [data] = unsigned bytes 0..255 (128 is the centre); [stereo]: left, right byte pairs (one pair per
 * frame; Hyper Voice on the Color only), else one byte per frame. 1..[MAX_FRAMES] frames. [rate] is the rate in Hz it
 * was converted or synthesized at (for display; what plays it decides its speed).
 */
@Serializable
data class WsSample(val name: String, val data: List<Int>, val stereo: Boolean = false, val rate: Int = WsSamples.DEFAULT_RATE_HZ) {
    /** Frames: bytes, or byte pairs when [stereo]. */
    val frames: Int get() = if (stereo) data.size / 2 else data.size

    /** Why the sample cannot load, or null. */
    fun problem(): String? = when {
        data.isEmpty() -> "has no frames"
        stereo && data.size % 2 != 0 -> "is stereo but has an odd number of bytes (${data.size})"
        frames > MAX_FRAMES -> "has $frames frames; samples have at most $MAX_FRAMES"
        rate <= 0 -> "has the rate $rate Hz"
        else -> data.firstOrNull { it !in 0..255 }?.let { "byte value $it is outside 0-255" }
    }

    companion object {
        /** Most frames of one sample (the image's u32 frame count is limited to 65535; the u16 loop start). */
        const val MAX_FRAMES = WsPlayerImage.MAX_SAMPLE_FRAMES

        /** Longest sample name. */
        const val MAX_NAME = 16
    }
}

/**
 * A WonderSwan song's chip data: [color] picks the system ([WscSystem] when true: five lanes, Sound DMA and Hyper
 * Voice; else [WsSystem]), [instruments] = exactly [WsRules.SLOTS] slots (slot n = index n − 1; null = empty), the song's
 * [waves] (0..[MAX_WAVES] tables of [WAVE_SIZE] values 0..[WAVE_MAX], in play order), its [samples] (0..[MAX_SAMPLES]),
 * the [output] it is heard through and the console's **shared** settings at the song start: the speaker's
 * [speakerShift] (0..3, `Kxx`) and the voice timer's [timerReload] (1..255: 12000 / R interrupts per second, `Txx`),
 * which feeds CPU-fed samples.
 */
@Serializable
data class WsSound(
    val color: Boolean,
    val instruments: List<WsInstrument?>,
    val waves: List<List<Int>>,
    val samples: List<WsSample> = emptyList(),
    val output: WsOutput = WsOutput.HEADPHONES,
    val speakerShift: Int = DEFAULT_SHIFT,
    val timerReload: Int = DEFAULT_TIMER,
) : SystemSound {
    override val system: SoundSystem get() = if (color) WscSystem else WsSystem

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): WsInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: WsInstrument?): WsSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Why a song-level value is out of range, or null. */
    fun problem(): String? {
        if (instruments.size != WsRules.SLOTS) return "has ${instruments.size} sound slots; WonderSwan songs have ${WsRules.SLOTS}"
        if (waves.size > MAX_WAVES) return "has ${waves.size} waves; the player holds at most $MAX_WAVES"
        waves.forEachIndexed { i, w ->
            if (w.size != WAVE_SIZE) return "wave ${hex2(i)} has ${w.size} steps; waves have $WAVE_SIZE"
            w.firstOrNull { it !in 0..WAVE_MAX }?.let { return "wave ${hex2(i)} has the value $it; wave values are 0-$WAVE_MAX" }
        }
        if (samples.size > MAX_SAMPLES) return "has ${samples.size} samples; the player holds at most $MAX_SAMPLES"
        samples.forEachIndexed { i, s -> s.problem()?.let { return "sample ${hex2(i)} \"${s.name}\" $it" } }
        return when {
            speakerShift !in 0..WsPlayerImage.MAX_SHIFT -> "speaker shift $speakerShift is outside 0-${WsPlayerImage.MAX_SHIFT}"
            timerReload !in 1..WsPlayerImage.MAX_TIMER -> "voice timer $timerReload is outside 1-${WsPlayerImage.MAX_TIMER}"
            else -> null
        }
    }

    companion object {
        const val WAVE_SIZE = WsPlayerImage.WAVE_SIZE
        const val WAVE_MAX = WsPlayerImage.WAVE_MAX
        const val MAX_WAVES = WsPlayerImage.MAX_WAVES
        const val MAX_SAMPLES = WsPlayerImage.MAX_SAMPLES

        /** A new song's voice timer reload: 12000 / 2 = 6000 interrupts per second, the starter kit's rate. */
        const val DEFAULT_TIMER = 2

        /**
         * A new song's speaker shift: 3 (12.5 %), the one setting at which the speaker's 8-bit sum never wraps, whatever
         * every channel plays.
         */
        const val DEFAULT_SHIFT = 3

        /**
         * A new song's sound for the WonderSwan Color when [color], else the original: the built-in waves
         * ([WsPresets.WAVES]), the presets the model plays in slots 01.. ([WsPresets.presets]; tones on the built-in
         * wave equal to their own, noise sounds; sample presets need samples and are left to the Preset row), the rest
         * empty; no samples, headphones, speaker shift 3, voice timer 6000 Hz.
         */
        fun blank(color: Boolean): WsSound {
            val waves = WsPresets.WAVES.map { it.second }
            val instruments = WsPresets.presets(color).filter { it.sample == null }.map { p ->
                val wave = p.wave?.let { w -> waves.indexOf(w) } ?: 0
                p.instrument.copy(wave = maxOf(wave, 0))
            }
            return WsSound(color, List(WsRules.SLOTS) { instruments.getOrNull(it) }, waves)
        }

        private fun hex2(v: Int) = NumberText.hex(v, 2)
    }
}

/** The WonderSwan data of a WonderSwan or WonderSwan Color song. */
val Song.ws: WsSound get() = sound as WsSound
