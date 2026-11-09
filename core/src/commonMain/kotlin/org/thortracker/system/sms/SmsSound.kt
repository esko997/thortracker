package org.thortracker.system.sms

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.nes.NesEnvelope

/**
 * One YM2413 (OPLL) operator of a custom voice: exactly the fields of registers $00/$01 (AM, vibrato, EG type =
 * [sustained], KSR, MULT), the key scale level of $02/$03, the rectified half-sine of $03 and $04-$07 (AR, DR, SL, RR).
 */
@Serializable
data class OpllOperator(
    /** Tremolo from the chip's fixed LFO (3.7 Hz, 4.8 dB). */
    val am: Boolean = false,
    /** Vibrato from the chip's fixed LFO (6.4 Hz, ±14 cents). */
    val vibrato: Boolean = false,
    /** EG type: true holds the sustain level while the key is down; false (percussive) keeps decaying at the release rate. */
    val sustained: Boolean = true,
    /** Key scale rate: faster envelopes on higher notes. */
    val ksr: Boolean = false,
    /** Frequency multiplier 0..15 (0 = ×½; the chip plays 11 as ×10, 13 as ×12, 14 as ×15). */
    val mult: Int = 1,
    /** Key scale level 0..3: quieter higher notes (0 off, 1 1.5, 2 3, 3 6 dB per octave). */
    val ksl: Int = 0,
    /** Attack rate 0..15 (0 never starts, 15 instant). */
    val ar: Int = 15,
    /** Decay rate 0..15, from full level to [sl]. */
    val dr: Int = 0,
    /** Sustain level 0..15: 3 dB steps below full. */
    val sl: Int = 0,
    /** Release rate 0..15. */
    val rr: Int = 7,
    /** The rectified (half) sine instead of the sine (DM for the modulator, DC for the carrier). */
    val rectified: Boolean = false,
) {
    /** Register $00/$01. */
    val reg0: Int get() = (if (am) 0x80 else 0) or (if (vibrato) 0x40 else 0) or (if (sustained) 0x20 else 0) or (if (ksr) 0x10 else 0) or mult

    /** Register $04/$05. */
    val reg4: Int get() = (ar shl 4) or dr

    /** Register $06/$07. */
    val reg6: Int get() = (sl shl 4) or rr

    fun problem(): String? = when {
        mult !in 0..15 -> "multiplier $mult is outside 0-15"
        ksl !in 0..3 -> "key scale level $ksl is outside 0-3"
        ar !in 0..15 || dr !in 0..15 || sl !in 0..15 || rr !in 0..15 -> "attack, decay, sustain and release must be 0-15"
        else -> null
    }
}

/**
 * The YM2413's one custom voice (instrument 0): a [modulator] into a [carrier], the modulator's total [level] 0..63
 * (0.75 dB steps; the brightness) and its self-[feedback] 0..7. The chip holds a single custom voice for all nine
 * channels: every channel playing instrument 0 sounds the voice last written.
 */
@Serializable
data class OpllPatch(
    val modulator: OpllOperator = OpllOperator(),
    val carrier: OpllOperator = OpllOperator(),
    val level: Int = 0,
    val feedback: Int = 0,
) {
    /** The eight registers $00-$07 Banjo writes when a channel changes to this voice. */
    fun registers(): IntArray = intArrayOf(
        modulator.reg0,
        carrier.reg0,
        (modulator.ksl shl 6) or level,
        (carrier.ksl shl 6) or (if (carrier.rectified) 0x10 else 0) or (if (modulator.rectified) 0x08 else 0) or feedback,
        modulator.reg4,
        carrier.reg4,
        modulator.reg6,
        carrier.reg6,
    )

    fun problem(): String? {
        if (level !in 0..63) return "modulator level $level is outside 0-63"
        if (feedback !in 0..7) return "feedback $feedback is outside 0-7"
        modulator.problem()?.let { return "modulator: $it" }
        carrier.problem()?.let { return "carrier: $it" }
        return null
    }

    companion object {
        /** A voice from its eight register bytes. */
        fun ofRegisters(r: IntArray): OpllPatch {
            fun op(r0: Int, ksl: Int, r4: Int, r6: Int, rect: Boolean) = OpllOperator(
                am = r0 and 0x80 != 0, vibrato = r0 and 0x40 != 0, sustained = r0 and 0x20 != 0, ksr = r0 and 0x10 != 0,
                mult = r0 and 15, ksl = ksl, ar = r4 shr 4 and 15, dr = r4 and 15, sl = r6 shr 4 and 15, rr = r6 and 15, rectified = rect,
            )
            return OpllPatch(
                modulator = op(r[0], r[2] shr 6 and 3, r[4], r[6], r[3] and 0x08 != 0),
                carrier = op(r[1], r[3] shr 6 and 3, r[5], r[7], r[3] and 0x10 != 0),
                level = r[2] and 0x3F,
                feedback = r[3] and 7,
            )
        }

        /** The YM2413's built-in voices 1-15 (index 0 unused), as emu2413 1.6.4 has measured them. */
        private val ROM_DUMPS = listOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0x71, 0x61, 0x1e, 0x17, 0xd0, 0x78, 0x00, 0x17),
            intArrayOf(0x13, 0x41, 0x1a, 0x0d, 0xd8, 0xf7, 0x23, 0x13),
            intArrayOf(0x13, 0x01, 0x99, 0x00, 0xf2, 0xc4, 0x21, 0x23),
            intArrayOf(0x11, 0x61, 0x0e, 0x07, 0x8d, 0x64, 0x70, 0x27),
            intArrayOf(0x32, 0x21, 0x1e, 0x06, 0xe1, 0x76, 0x01, 0x28),
            intArrayOf(0x31, 0x22, 0x16, 0x05, 0xe0, 0x71, 0x00, 0x18),
            intArrayOf(0x21, 0x61, 0x1d, 0x07, 0x82, 0x81, 0x11, 0x07),
            intArrayOf(0x33, 0x21, 0x2d, 0x13, 0xb0, 0x70, 0x00, 0x07),
            intArrayOf(0x61, 0x61, 0x1b, 0x06, 0x64, 0x65, 0x10, 0x17),
            intArrayOf(0x41, 0x61, 0x0b, 0x18, 0x85, 0xf0, 0x81, 0x07),
            intArrayOf(0x33, 0x01, 0x83, 0x11, 0xea, 0xef, 0x10, 0x04),
            intArrayOf(0x17, 0xc1, 0x24, 0x07, 0xf8, 0xf8, 0x22, 0x12),
            intArrayOf(0x61, 0x50, 0x0c, 0x05, 0xd2, 0xf5, 0x40, 0x42),
            intArrayOf(0x01, 0x01, 0x55, 0x03, 0xe9, 0x90, 0x03, 0x02),
            intArrayOf(0x41, 0x41, 0x89, 0x03, 0xf1, 0xe4, 0xc0, 0x13),
        )

        /** Built-in voice [patch] (1..15) as a custom voice to start editing from. */
        fun rom(patch: Int): OpllPatch = ofRegisters(ROM_DUMPS[patch.coerceIn(1, 15)])

        /** The built-in voices' names (index 0 = the custom voice). */
        val PATCH_NAMES = listOf(
            "Custom", "Violin", "Guitar", "Piano", "Flute", "Clarinet", "Oboe", "Trumpet", "Organ", "Horn", "Synthesizer",
            "Harpsichord", "Vibraphone", "Synth bass", "Acoustic bass", "Electric guitar",
        )
    }
}

/**
 * A Master System instrument slot's sound: a PSG sound for Square 1-3 and Noise ([SmsPsgInstrument]), a YM2413 voice
 * for the FM channels ([SmsFmInstrument]) or a rhythm kit for the rhythm-mode drum channels ([SmsDrumKit]). Stored with
 * a `"type"` of `"psg"`, `"fm"` or `"kit"`. Each lowers to one 12-byte Banjo instrument record.
 */
@Serializable
sealed interface SmsInstrument {
    val name: String
    val kind: SmsInstrumentKind

    /** Why the instrument cannot be lowered, or null. */
    fun problem(): String?

    fun named(name: String): SmsInstrument
}

/**
 * A PSG sound: Banjo's instrument macros, one value per tick — [volume] 0..15 (empty = full; its release part plays
 * after OFF), [arpeggio] −64..63 semitones from the note, and one "extra" macro: [pitch] −64..63 in 1/128 semitone
 * (absolute per tick, ±½ semitone; Banjo resolves 1/16) or [noise], the SN76489's noise mode per tick (0 periodic, 1
 * white, +2 pitched by Square 3's period). At most one of [pitch] and [noise] has steps. Loop and release follow
 * [NesEnvelope]; after OFF the volume loop runs out into its release part (Banjo's macro release).
 */
@Serializable
@SerialName("psg")
data class SmsPsgInstrument(
    override val name: String = "",
    val volume: NesEnvelope = NesEnvelope(),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val noise: NesEnvelope = NesEnvelope(),
) : SmsInstrument {
    override val kind: SmsInstrumentKind get() = SmsInstrumentKind.PSG

    override fun named(name: String): SmsPsgInstrument = copy(name = name)

    override fun problem(): String? {
        SmsInstruments.envelopeProblem("volume", volume, NesEnvelope.VOLUME, SmsInstruments.VOLUME_MAX, releasable = true)?.let { return it }
        SmsInstruments.envelopeProblem("arpeggio", arpeggio, NesEnvelope.ARPEGGIO, SmsInstruments.MACRO_MAX, releasable = false)?.let { return it }
        SmsInstruments.envelopeProblem("pitch", pitch, NesEnvelope.PITCH, SmsInstruments.MACRO_MAX, releasable = false)?.let { return it }
        SmsInstruments.envelopeProblem("noise", noise, NesEnvelope.DUTY, SmsInstruments.MACRO_MAX, releasable = false)?.let { return it }
        if (pitch.values.isNotEmpty() && noise.values.isNotEmpty()) {
            return "has both a pitch and a noise envelope; a Banjo instrument has one extra envelope"
        }
        return null
    }
}

/**
 * A YM2413 voice: built-in [patch] 1..15 or 0 = the [custom] voice (the chip has one custom voice at a time; Banjo
 * writes it when a channel changes to this instrument), plus Banjo's [volume] 0..15 and [arpeggio] macros. [custom] is
 * kept while a built-in patch is chosen, so switching back loses nothing.
 */
@Serializable
@SerialName("fm")
data class SmsFmInstrument(
    override val name: String = "",
    val patch: Int = 1,
    val custom: OpllPatch = OpllPatch.rom(1),
    val volume: NesEnvelope = NesEnvelope(),
    val arpeggio: NesEnvelope = NesEnvelope(),
) : SmsInstrument {
    override val kind: SmsInstrumentKind get() = SmsInstrumentKind.FM

    val isCustom: Boolean get() = patch == 0

    override fun named(name: String): SmsFmInstrument = copy(name = name)

    override fun problem(): String? {
        if (patch !in 0..15) return "patch $patch is outside 0 (custom) and 1-15"
        custom.problem()?.let { return "custom voice: $it" }
        SmsInstruments.envelopeProblem("volume", volume, NesEnvelope.VOLUME, SmsInstruments.VOLUME_MAX, releasable = true)?.let { return it }
        return SmsInstruments.envelopeProblem("arpeggio", arpeggio, NesEnvelope.ARPEGGIO, SmsInstruments.MACRO_MAX, releasable = false)
    }
}

/**
 * A rhythm kit for the YM2413's rhythm-mode drums (Banjo's `FM_DRUM`): pitched by each drum note ([fixed] false; the
 * note sets the pitch of the drum's channel: the bass drum its own, the snare with the hi-hat and the tom with the
 * cymbal share one each) or [fixed] pitches set when a drum channel loads the kit: [kick], [snareHat], [tomCymbal]
 * (absolute notes 0..95, as F-number and block from Banjo's table). The mode is the chip's: one for all five drums.
 */
@Serializable
@SerialName("kit")
data class SmsDrumKit(
    override val name: String = "",
    val fixed: Boolean = false,
    val kick: Int = 33,
    val snareHat: Int = 36,
    val tomCymbal: Int = 17,
) : SmsInstrument {
    override val kind: SmsInstrumentKind get() = SmsInstrumentKind.KIT

    override fun named(name: String): SmsDrumKit = copy(name = name)

    override fun problem(): String? =
        if (listOf(kick, snareHat, tomCymbal).any { it !in 0..SmsInstruments.FM_TOP }) "drum pitches must be C-0..B-7 (0-95)" else null
}

/** Limits shared by the instrument checks. */
object SmsInstruments {
    /** Highest FM note: block 7. */
    const val FM_TOP = 95

    /** Volume macros carry a 4-byte header and their length counts it: at most 251 steps. */
    const val VOLUME_MAX = 251

    /** Arpeggio and extra macros carry a 2-byte header: at most 253 steps. */
    const val MACRO_MAX = 253

    /** Why [env] cannot be lowered as the [what] envelope, or null. */
    fun envelopeProblem(what: String, env: NesEnvelope, range: IntRange, max: Int, releasable: Boolean): String? {
        if (env.values.isEmpty()) {
            return if (env.loop != -1 || env.release != -1) "the empty $what envelope has a loop or release point" else null
        }
        val n = env.values.size
        return when {
            n > max -> "the $what envelope has $n values; Banjo's macros hold at most $max"
            env.values.any { it !in range } -> "$what envelope values must be ${range.first}..${range.last}"
            env.loop != -1 && env.loop !in 0 until n -> "the $what envelope's loop point ${env.loop} is past its end"
            env.release != -1 && !releasable -> "only the volume envelope has a release point"
            env.release != -1 && env.loop == -1 -> "the $what envelope's release point needs a loop before it"
            env.release != -1 && (env.release <= env.loop || env.release >= n) ->
                "the $what envelope's release point must lie after its loop point and before its end"
            env.release < -1 || env.loop < -1 -> "the $what envelope's loop and release points must be -1 (none) or an index"
            else -> null
        }
    }

    /** True when the instrument's volume envelope has a release part (OFF plays it instead of stopping the note). */
    fun releases(ins: SmsInstrument?): Boolean = when (ins) {
        is SmsPsgInstrument -> ins.volume.release >= 0
        is SmsFmInstrument -> ins.volume.release >= 0
        else -> false
    }
}

/**
 * A Master System song's chip data: its [layout] (which system it belongs to) and [instruments] = exactly
 * [SmsSystem.SLOTS] slots (slot n = index n − 1; null = empty). Stored as `{"instruments": [...]}`; the layout comes
 * from the project's backend id ([SmsSoundSerializer]).
 */
data class SmsSound(val layout: SmsLayout, val instruments: List<SmsInstrument?>) : SystemSound {
    override val system: SoundSystem get() = SmsSystem.of(layout)

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): SmsInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: SmsInstrument?): SmsSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    companion object {
        /** A new song's sound: the presets the layout plays in slots 01.., the rest empty. */
        fun blank(layout: SmsLayout): SmsSound {
            val presets = SmsPresets.forLayout(layout)
            return SmsSound(layout, List(SmsSystem.SLOTS) { presets.getOrNull(it)?.instrument })
        }
    }
}

/** The stored shape of [SmsSound]. */
@Serializable
internal data class SmsSoundFile(val instruments: List<SmsInstrument?>)

/** [SmsSound] in project files: the instruments; the layout is the system's ([SmsSystem.soundSerializer]). */
@OptIn(ExperimentalSerializationApi::class)
class SmsSoundSerializer(private val layout: SmsLayout) : KSerializer<SmsSound> {
    private val delegate = SmsSoundFile.serializer()

    override val descriptor: SerialDescriptor = SerialDescriptor("org.thortracker.system.sms.SmsSound.${layout.id}", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: SmsSound) {
        encoder.encodeSerializableValue(delegate, SmsSoundFile(value.instruments))
    }

    override fun deserialize(decoder: Decoder): SmsSound = SmsSound(layout, decoder.decodeSerializableValue(delegate).instruments)
}

/** The Master System data of a Banjo song. */
val Song.sms: SmsSound get() = sound as SmsSound
