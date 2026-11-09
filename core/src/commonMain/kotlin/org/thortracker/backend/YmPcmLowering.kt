package org.thortracker.backend

import org.thortracker.backend.YmPlayerImage.PCM_ALIGN
import org.thortracker.backend.YmPlayerImage.PCM_BANK_SIZE
import org.thortracker.backend.YmPlayerImage.PCM_MAX_VARIANTS
import org.thortracker.model.NumberText
import org.thortracker.system.ym2612.YmPcm
import org.thortracker.system.ym2612.YmPcmInstrument
import org.thortracker.system.ym2612.YmPcmSample
import org.thortracker.system.ym2612.YmSound
import org.thortracker.system.ym2612.YmSystem
import kotlin.math.roundToLong

/**
 * The Mega Drive PCM bank: every key of every PCM kit compiled ahead of time to a one-shot signed 8-bit waveform at the
 * SGDK XGM2 driver's full rate, MCLK / [YmPlayerImage.PCM_FULL_DIVIDER] (13316.8 Hz NTSC, 13195.3 Hz PAL), so playback,
 * auditions and exports never resample. (The driver's half-speed music samples would still take a full-rate region of
 * the bank, its second half silent, so they save nothing: every variant is full rate, flags 0.)
 *
 * Compilation is integer arithmetic only, so every platform (and the Python reference builder) makes the same bytes:
 * the step through the source is source rate × [SEMITONE_Q28] × [YmPlayerImage.PCM_FULL_DIVIDER] × 2^(octave + 4) /
 * MCLK in Q32 (floored), positions are j × step, values interpolate linearly between neighbouring source bytes (past
 * the last byte towards 0, silence), the kit's level scales by [LEVEL_Q15] (Q15, 2 dB per step, 0 silent), and each
 * waveform is padded with 0 to [PCM_ALIGN].
 *
 * Variants are numbered in first use, slots 1..64 then keys 0..95: a (sample, pitch, level) met before reuses its id,
 * and a waveform byte-identical to an earlier one shares that id. The bank is the variants in id order, contiguous
 * from 0 (XGM2's sample table takes each length from the next start). Beyond [PCM_MAX_VARIANTS] distinct waveforms or
 * [PCM_BANK_SIZE] bytes the song is refused, never cut: an authoring limit of Thor Tracker's image, not of the console.
 */
object YmPcmLowering {
    /** 2^(s/12), s = 0..11, in Q28: exact power-of-two scaling of the doubles, rounded. */
    private val SEMITONE_Q28: LongArray = doubleArrayOf(
        1.0, 1.0594630943592953, 1.122462048309373, 1.189207115002721, 1.2599210498948732, 1.3348398541700344,
        1.4142135623730951, 1.4983070768766815, 1.5874010519681994, 1.681792830507429, 1.7817974362806785, 1.8877486253633868,
    ).let { r -> LongArray(12) { (r[it] * (1L shl 28).toDouble()).roundToLong() } }

    /** Kit level 0..15 as a Q15 gain: 15 = ×1, each step down 2 dB, 0 silent. */
    val LEVEL_Q15 = intArrayOf(0, 1305, 1642, 2068, 2603, 3277, 4125, 5193, 6538, 8231, 10362, 13045, 16423, 20675, 26029, 32768)

    /** One compiled waveform: [bytes] padded to [PCM_ALIGN], of which the first [length] are the sound. */
    class Variant(val bytes: ByteArray, val length: Int) {
        val pages: Int get() = bytes.size / PCM_ALIGN
    }

    /**
     * The compiled bank: [variants] in id order (id = index + 1), [ids] each (slot, key) → variant id, [pages] the
     * bank's size in [PCM_ALIGN]-byte pages.
     */
    class Bank(val variants: List<Variant>, val ids: Map<Pair<Int, Int>, Int>, val pages: Int) {
        val bytes: Int get() = pages * PCM_ALIGN

        /** Variant id of [key] on PCM kit [slot], or 0 when unmapped. */
        fun id(slot: Int, key: Int): Int = ids[slot to key] ?: 0
    }

    private val EMPTY = Bank(emptyList(), emptyMap(), 0)

    /** The inputs a bank depends on, for [compile]'s one-entry memo (equal kits and sources compile alike). */
    private data class Inputs(val pal: Boolean, val samples: List<YmPcmSample>, val kits: List<YmPcmInstrument?>)

    private class Memo(val inputs: Inputs, val bank: Bank?, val problem: String?)

    /** The last compilation: lowering, validation and auditions of an unchanged song share it (replaced whole, never mutated). */
    private var last: Memo? = null

    /**
     * [sound]'s compiled PCM bank.
     * @throws IllegalArgumentException with an actionable message when the kits do not fit the bank or a mapping is broken.
     */
    fun compile(sound: YmSound): Bank {
        val memo = memo(sound)
        return memo.bank ?: throw IllegalArgumentException(memo.problem)
    }

    /** Why [sound]'s PCM kits cannot compile, or null. */
    fun problem(sound: YmSound): String? = memo(sound).problem

    private fun memo(sound: YmSound): Memo {
        val inputs = Inputs(sound.pal, sound.pcmSamples, sound.instruments.map { it as? YmPcmInstrument })
        last?.let { if (it.inputs == inputs) return it }
        val made = try {
            Memo(inputs, build(inputs), null)
        } catch (e: IllegalArgumentException) {
            Memo(inputs, null, e.message ?: "The PCM kits cannot be compiled")
        }
        last = made
        return made
    }

    private data class Key(val sample: Int, val pitch: Int, val level: Int)

    private class Wave(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Wave && other.bytes.contentEquals(bytes)
    }

    private fun build(inputs: Inputs): Bank {
        if (inputs.kits.none { it != null && it.pcm.isNotEmpty() }) return EMPTY
        val mclk = mclk(inputs.pal)
        val variants = ArrayList<Variant>()
        val byKey = HashMap<Key, Int>()
        val byWave = HashMap<Wave, Int>()
        val ids = LinkedHashMap<Pair<Int, Int>, Int>()
        var pages = 0
        val maxPages = PCM_BANK_SIZE / PCM_ALIGN
        inputs.kits.forEachIndexed { i, kit ->
            if (kit == null) return@forEachIndexed
            val slot = i + 1
            for (key in kit.pcm.keys.sorted()) {
                val m = kit.pcm.getValue(key)
                val sample = inputs.samples.getOrNull(m.sample)
                    ?: throw IllegalArgumentException("Instrument ${hex2(slot)} maps ${YmSystem.noteName(key)} to missing PCM sample ${m.sample + 1}")
                require(m.pitch in YmPcm.PITCH && kit.level in LEVEL_Q15.indices && sample.problem() == null) {
                    "Instrument ${hex2(slot)} ${YmSystem.noteName(key)}: ${sample.problem() ?: "pitch ${m.pitch} or level ${kit.level} is out of range"}"
                }
                val k = Key(m.sample, m.pitch, kit.level)
                val known = byKey[k]
                if (known != null) { ids[slot to key] = known; continue }
                val step = step(sample.rate, m.pitch, mclk)
                val length = length(sample.data.size, step)
                val need = ((length.toLong() + PCM_ALIGN - 1) / PCM_ALIGN).toInt()
                if (pages + need > maxPages) throw IllegalArgumentException(full(inputs, slot, key, pages, need))
                val wave = Wave(waveform(sample, step, length, kit.level, need * PCM_ALIGN))
                val id = byWave[wave] ?: run {
                    if (variants.size >= PCM_MAX_VARIANTS) {
                        throw IllegalArgumentException("The PCM kits need more than $PCM_MAX_VARIANTS different compiled sounds " +
                            "(instrument ${hex2(slot)} ${YmSystem.noteName(key)} is number ${PCM_MAX_VARIANTS + 1}); XGM2 plays at most " +
                            "$PCM_MAX_VARIANTS per song. Map fewer pitched keys, share samples between kits, or reuse pitches and levels")
                    }
                    variants += Variant(wave.bytes, length)
                    pages += need
                    variants.size.also { byWave[wave] = it }
                }
                byKey[k] = id
                ids[slot to key] = id
            }
        }
        return Bank(variants, ids, pages)
    }

    private fun full(inputs: Inputs, slot: Int, key: Int, pages: Int, need: Int): String =
        "The PCM kits compile to more than ${PCM_BANK_SIZE / 1024} KB: instrument ${hex2(slot)} ${YmSystem.noteName(key)} needs " +
            "${YmPcm.kb(need * PCM_ALIGN)} with ${YmPcm.kb(pages * PCM_ALIGN)} already used (every mapped key is compiled at " +
            "the driver's ${YmPcm.rateText(YmPcm.fullRate(inputs.pal))}; keys pitched down make longer sounds). Use shorter " +
            "samples, fewer or higher-pitched keys, or delete unused mappings"

    /** The master clock of the region. */
    fun mclk(pal: Boolean): Long = (if (pal) YmPlayerImage.MCLK_PAL else YmPlayerImage.MCLK_NTSC).toLong()

    /** Source samples advanced per output sample at the driver's full rate, Q32, floored. */
    fun step(rate: Int, pitch: Int, mclk: Long): Long {
        val octave = pitch.floorDiv(12)
        val a = rate.toLong() * SEMITONE_Q28[pitch.mod(12)] * YmPlayerImage.PCM_FULL_DIVIDER
        val shift = 4 + octave
        return ((a / mclk) shl shift) + (((a % mclk) shl shift) / mclk)
    }

    /** Output samples for a source of [size] bytes: every position j × [step] inside it. */
    fun length(size: Int, step: Long): Int = (((size.toLong() shl 32) + step - 1) / step).toInt()

    private fun waveform(sample: YmPcmSample, step: Long, length: Int, level: Int, padded: Int): ByteArray {
        val out = ByteArray(padded)
        val gain = LEVEL_Q15[level]
        if (gain == 0) return out
        val data = sample.data
        val n = data.size
        var pos = 0L
        for (j in 0 until length) {
            val i = (pos ushr 32).toInt()
            val f = ((pos ushr 16) and 0xFFFF).toInt()
            val a = data.signed(i)
            val b = if (i + 1 < n) data.signed(i + 1) else 0
            val v = a + (((b - a) * f) shr 16)
            out[j] = ((v * gain) shr 15).coerceIn(-128, 127).toByte()
            pos += step
        }
        return out
    }

    /** "12 of 123 sounds, 84.0 KB of 1024 KB": the bank's use for the song check. */
    fun usage(bank: Bank): String = "${bank.variants.size} of $PCM_MAX_VARIANTS sounds, ${YmPcm.kb(bank.bytes)} of ${PCM_BANK_SIZE / 1024} KB"

    /**
     * Writes [bank] into [out]: each kit key's variant id at [YmPlayerImage.OFF_PCM_KEYS], the variant table at
     * [YmPlayerImage.OFF_PCM_VARIANTS] (start and length in pages, flags 0: full rate) and the waveforms from
     * [YmPlayerImage.OFF_PCM_BANK]; the header fields are the caller's. [out] is zeroed beforehand.
     */
    fun write(bank: Bank, out: ByteArray) {
        for ((at, id) in bank.ids) {
            val (slot, key) = at
            out[YmPlayerImage.OFF_PCM_KEYS + YmPlayerImage.PCM_KEYS * (slot - 1) + key] = id.toByte()
        }
        var page = 0
        bank.variants.forEachIndexed { i, v ->
            val entry = YmPlayerImage.OFF_PCM_VARIANTS + YmPlayerImage.PCM_VARIANT_SIZE * i
            u16(out, entry, page)
            u16(out, entry + 2, v.pages)
            v.bytes.copyInto(out, YmPlayerImage.OFF_PCM_BANK + page * PCM_ALIGN)
            page += v.pages
        }
    }

    private fun u16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun hex2(v: Int) = NumberText.hex(v, 2)
}
