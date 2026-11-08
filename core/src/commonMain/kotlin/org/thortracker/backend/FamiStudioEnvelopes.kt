package org.thortracker.backend

import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

/**
 * FamiStudio 4.5.3's envelope encoding, ported from its exporter (`FamitoneMusicFile.ProcessEnvelope`,
 * `ProcessMixerEnvelope`, `CleanupEnvelopes`, `Envelope.Optimize`, `Envelope.CreateVibratoEnvelope`) so the bytes the
 * engine reads are the ones FamiStudio itself would export. Envelope bytes: a value is byte − 192 (`$80-$FF`),
 * `$01-$7F` holds the previous value that many frames, `$00 n` jumps to index n. Volume envelopes start with their
 * release index, pitch envelopes with the relative flag (`$80`).
 */
internal object FamiStudioEnvelopes {
    /**
     * Envelope kinds in the image's directory order (the ordinal is the directory's kind byte): the 2A03 four, then the
     * SSG mixer (FamiStudio `S5BMixer`) and noise period (`S5BNoiseFreq`) envelopes (5B and EPSM sounds).
     */
    enum class Kind { VOLUME, ARPEGGIO, DUTY, PITCH, MIXER, NOISE }

    /** An envelope as FamiStudio holds it after import and the exporter's clean-up (see [effective]). */
    data class Env(val values: List<Int>, val loop: Int, val release: Int, val relative: Boolean)

    /** Instrument envelope "not used": FamiStudio's defaults. */
    val DEFAULT_ENV = byteArrayOf(0xc0.toByte(), 0x7f, 0x00, 0x01)
    val DEFAULT_DUTY_ENV = byteArrayOf(0x7f, 0x00, 0x00)
    val DEFAULT_PITCH_OR_RELEASE_ENV = byteArrayOf(0x00, 0xc0.toByte(), 0x7f, 0x00, 0x02)

    private val VIBRATO_SPEEDS = intArrayOf(0, 64, 32, 21, 16, 13, 11, 9, 8, 7, 6, 5, 4)
    private val VIBRATO_DEPTHS = intArrayOf(0x01, 0x03, 0x05, 0x07, 0x09, 0x0d, 0x13, 0x17, 0x1b, 0x21, 0x2b, 0x3b, 0x57, 0x7f, 0xbf, 0xff)

    /**
     * [e] of [kind] as FamiStudio has it once its text import has read it (loop and release clamped as its setters
     * do: a release needs a loop, loop < release < length; only volume releases), an empty envelope (no values or
     * all at the type's neutral value) replaced by FamiStudio's default, and the exporter's clean-up (`CleanupEnvelopes`,
     * then `Optimize` for non-empty envelopes). Two instruments with equal effective envelopes are "identical" for
     * FamiStudio's legato rule. A mixer envelope's neutral value is 2 (tone only: FamiStudio's default, one step).
     */
    fun effective(e: NesEnvelope, kind: Kind, relative: Boolean = false): Env {
        val zero = when (kind) {
            Kind.VOLUME -> 15
            Kind.MIXER -> MIXER_TONE
            else -> 0
        }
        if (e.values.isEmpty() || e.values.all { it == zero }) {
            return when (kind) {
                Kind.VOLUME -> Env(listOf(15), -1, -1, false)
                Kind.DUTY -> Env(listOf(0), -1, -1, false)
                Kind.MIXER -> Env(listOf(MIXER_TONE), -1, -1, false)
                else -> Env(List(8) { 0 }, -1, -1, false)
            }
        }
        val length = e.values.size
        var loop = if (e.loop >= 0) minOf(e.loop, length).let { if (it >= length) -1 else it } else -1
        var release = if (kind == Kind.VOLUME && e.release >= 0 && loop >= 0) e.release.coerceIn(loop + 1, length) else -1
        if (release >= length) release = -1
        val rel = kind == Kind.PITCH && relative
        var values = e.values
        // ProcessEnvelope leaves all-zero envelopes alone (it returns the default before optimising them).
        if (values.all { it == 0 }) return Env(values, loop, release, rel)
        if (!rel) {
            if (values.all { it == values[0] }) {
                values = listOf(values[0]); loop = -1; release = -1
            } else if (loop < 0) {
                var i = length - 2
                while (i >= 0 && i > release && values[i] == values[i + 1]) i--
                values = values.subList(0, i + 2)
                if (release >= values.size) release = -1
            }
        }
        return Env(values, loop, release, rel)
    }

    /** The bytes FamiStudio exports for instrument envelope [e] of [kind]. */
    fun encode(e: NesEnvelope, kind: Kind, relative: Boolean = false): ByteArray {
        val env = effective(e, kind, relative).let { if (kind == Kind.MIXER) it.copy(values = it.values.map(::mixerBits)) else it }
        return process(env, kind == Kind.VOLUME, kind == Kind.PITCH) ?: when (kind) {
            Kind.VOLUME, Kind.PITCH -> DEFAULT_PITCH_OR_RELEASE_ENV
            Kind.DUTY -> DEFAULT_DUTY_ENV
            Kind.ARPEGGIO, Kind.MIXER, Kind.NOISE -> DEFAULT_ENV
        }
    }

    /** The mixer value FamiStudio's default (and an empty envelope) plays: tone only. */
    const val MIXER_TONE = 2

    /**
     * `ProcessMixerEnvelope`: a mixer value 0-3 (bit 0 tone off, bit 1 noise off) as the engine reads it, tone-off in
     * bit 0 and noise-off in bit 3, so square n's value shifted left by n gives its bits of the SSG's `$07` register.
     */
    fun mixerBits(value: Int): Int = (value and 1) or ((value and 2) shl 2)

    /** The arpeggio FamiStudio plays for effect `0xy`: note, +x, +y, looping. */
    fun arpeggio(param: Int): ByteArray =
        process(Env(listOf(0, param shr 4, param and 0xF), 0, -1, false), allowReleases = false, newPitchEnvelope = false)!!

    /** FamiStudio's vibrato pitch envelope for raw vibrato byte [raw] (speed 1-C in the high nibble, depth low). */
    fun vibrato(raw: Int): ByteArray {
        val speed = raw shr 4
        val depth = raw and 0xF
        val length = VIBRATO_SPEEDS[speed]
        val half = VIBRATO_DEPTHS[depth] / 2
        val values = List(length) { i -> round(-sin((i * 2.0f).toDouble() * PI / length) * half).toInt().toByte().toInt() }
        return process(Env(values, 0, -1, false), allowReleases = false, newPitchEnvelope = true)!!
    }

    /** `ProcessEnvelope`: null for an all-zero (empty) envelope. [env] is already [effective]. */
    private fun process(env: Env, allowReleases: Boolean, newPitchEnvelope: Boolean): ByteArray? {
        if (env.values.all { it == 0 }) return null
        val length = env.values.size
        if (length == 1 && !env.relative && env.release < 0) {
            val v = (192 + env.values[0]).toByte()
            return if (newPitchEnvelope || allowReleases) byteArrayOf(0x00, v, 0x7f, 0x00, 0x02) else byteArrayOf(v, 0x7f, 0x00, 0x00)
        }
        // FamiStudio fills a 256-byte array with a byte index; longer results are not exportable (checked by callers).
        val data = IntArray(600)
        var ptr = if (allowReleases || newPitchEnvelope) 1 else 0
        var ptrLoop = 0xff
        var relCount = 0
        var prevVal = (env.values[0] + 1) and 0xff
        var foundRelease = false
        val allowRle = !env.relative
        if (newPitchEnvelope) data[0] = if (env.relative) 0x80 else 0x00
        for (j in 0 until length) {
            val v = (env.values[j].coerceIn(-64, 63) + 192) and 0xff
            if (prevVal != v || !allowRle || j == env.loop || (allowReleases && j == env.release) || j == length - 1 ||
                (relCount == 127 && newPitchEnvelope)) {
                if (relCount != 0) {
                    if (relCount == 1) data[ptr++] = prevVal
                    else {
                        while (relCount > 127) {
                            data[ptr++] = 127
                            relCount -= 127
                        }
                        data[ptr++] = relCount
                    }
                    relCount = 0
                }
                if (j == env.loop) ptrLoop = ptr
                if (j == env.release && allowReleases) {
                    foundRelease = true
                    data[ptr++] = 0
                    data[ptr++] = ptrLoop
                    data[0] = ptr
                }
                data[ptr++] = v
                prevVal = v
            } else {
                relCount++
            }
        }
        if (ptrLoop == 0xff || foundRelease) {
            if (newPitchEnvelope && env.relative && data[ptr - 1] != 192) data[ptr++] = 192
            ptrLoop = ptr - 1
        }
        data[ptr++] = 0
        data[ptr++] = ptrLoop
        return ByteArray(ptr) { data[it].toByte() }
    }

    /** FamiStudio's legato rule (`Channel.CanDisableAttack`, `Instrument.AllEnvelopesAreIdentical`: every envelope type). */
    fun allIdentical(a: NesInstrument, b: NesInstrument): Boolean =
        effective(a.volume, Kind.VOLUME) == effective(b.volume, Kind.VOLUME) &&
            effective(a.arpeggio, Kind.ARPEGGIO) == effective(b.arpeggio, Kind.ARPEGGIO) &&
            effective(a.pitch, Kind.PITCH, a.pitchRelative) == effective(b.pitch, Kind.PITCH, b.pitchRelative) &&
            effective(a.duty, Kind.DUTY) == effective(b.duty, Kind.DUTY) &&
            effective(a.mixer, Kind.MIXER) == effective(b.mixer, Kind.MIXER) &&
            effective(a.noise, Kind.NOISE) == effective(b.noise, Kind.NOISE)
}
