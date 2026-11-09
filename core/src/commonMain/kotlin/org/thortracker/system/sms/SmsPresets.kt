package org.thortracker.system.sms

import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope

/**
 * Starting sounds for Master System instruments: a new song has the ones its layout plays in slots 01.. (PSG tones,
 * noise drums, then FM voices and drum kits with the FM unit) and the instrument editor's Preset control loads any
 * of them. Noise presets set the noise mode with a one-step noise envelope; [Preset.note] is the note they were voiced
 * at (on the Noise channel the note picks the fixed rate: C /2048, C# /1024, D-B /512).
 */
object SmsPresets {
    enum class Category { PSG, NOISE, FM, KIT }

    data class Preset(val instrument: SmsInstrument, val category: Category, val note: Int? = null) {
        val name: String get() = instrument.name
    }

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    private fun psg(name: String, volume: NesEnvelope, arpeggio: NesEnvelope = NesEnvelope(), pitch: NesEnvelope = NesEnvelope(),
                    noise: NesEnvelope = NesEnvelope()) = SmsPsgInstrument(name, volume, arpeggio, pitch, noise)

    private fun op(mult: Int, ar: Int, dr: Int, sl: Int, rr: Int, sustained: Boolean = true, vibrato: Boolean = false,
                   rectified: Boolean = false, ksl: Int = 0, ksr: Boolean = false) =
        OpllOperator(vibrato = vibrato, sustained = sustained, ksr = ksr, mult = mult, ksl = ksl, ar = ar, dr = dr, sl = sl, rr = rr, rectified = rectified)

    private fun custom(name: String, modulator: OpllOperator, carrier: OpllOperator, level: Int, feedback: Int, volume: NesEnvelope = NesEnvelope()) =
        SmsFmInstrument(name, 0, OpllPatch(modulator, carrier, level, feedback), volume)

    private val BUILT_IN = (1..15).map { p -> Preset(SmsFmInstrument(OpllPatch.PATCH_NAMES[p], p, OpllPatch.rom(p)), Category.FM) }

    val ALL: List<Preset> = listOf(
        // PSG tones: volume envelopes with a release part after OFF.
        Preset(psg("Square lead", env(15, 14, 13, 12, 11, 11, 10, 10, 8, 6, 4, 2, 0, loop = 6, release = 8),
            pitch = env(0, 0, 0, 0, 0, 0, 0, 0, 8, 16, 8, 0, -8, -16, -8, loop = 8)), Category.PSG),
        Preset(psg("Square pluck", env(15, 13, 11, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 0)), Category.PSG),
        Preset(psg("Soft pad", env(4, 6, 8, 10, 11, 12, 12, 10, 8, 6, 4, 2, 0, loop = 6, release = 7)), Category.PSG),
        Preset(psg("PSG bass", env(15, 15, 14, 14, 13, 13, 12, 9, 6, 3, 0, loop = 6, release = 7)), Category.PSG),
        Preset(psg("Arp major", env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7), arpeggio = env(0, 0, 4, 4, 7, 7, loop = 0)), Category.PSG),
        Preset(psg("Arp minor", env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7), arpeggio = env(0, 0, 3, 3, 7, 7, loop = 0)), Category.PSG),
        // Noise: the mode envelope's one step (0 periodic, 1 white, +2 pitched by Square 3) makes the drum.
        Preset(psg("Noise snare", env(15, 13, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0), noise = env(1)), Category.NOISE, 49),
        Preset(psg("Noise hat", env(14, 10, 6, 3, 1, 0), noise = env(1)), Category.NOISE, 50),
        Preset(psg("Noise open hat", env(14, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0), noise = env(1)), Category.NOISE, 50),
        Preset(psg("Noise kick", env(15, 12, 9, 6, 3, 0), noise = env(0)), Category.NOISE, 48),
        Preset(psg("Noise tom", env(15, 14, 12, 10, 8, 6, 4, 2, 1, 0), arpeggio = env(0, -1, -2, -3, -4, -5, -6), noise = env(3)), Category.NOISE, 57),
        Preset(psg("Buzz bass", env(15, 15, 14, 14, 13, 13, 9, 5, 0, loop = 5, release = 6), noise = env(2)), Category.NOISE, 60),
    ) + BUILT_IN + listOf(
        // Custom voices (one at a time on the chip).
        Preset(custom("FM square lead", op(2, 15, 2, 2, 7), op(1, 15, 1, 1, 7), level = 26, feedback = 5), Category.FM),
        Preset(custom("FM slap bass", op(1, 15, 6, 6, 9, rectified = true), op(1, 15, 4, 3, 8), level = 22, feedback = 6), Category.FM),
        Preset(custom("FM bell", op(7, 15, 5, 5, 4, sustained = false), op(1, 15, 4, 6, 4, sustained = false), level = 28, feedback = 2), Category.FM),
        Preset(custom("FM brass", op(1, 10, 2, 2, 7, vibrato = true), op(1, 12, 1, 1, 7, vibrato = true), level = 20, feedback = 5), Category.FM),
        Preset(custom("FM e-piano", op(14, 15, 7, 8, 6, sustained = false), op(1, 15, 3, 4, 6), level = 34, feedback = 1), Category.FM),
        // Rhythm kits (FM unit, rhythm mode).
        Preset(SmsDrumKit("Pitched kit", fixed = false), Category.KIT),
        Preset(SmsDrumKit("Standard kit", fixed = true, kick = 33, snareHat = 36, tomCymbal = 17), Category.KIT),
        Preset(SmsDrumKit("Deep kit", fixed = true, kick = 26, snareHat = 31, tomCymbal = 12), Category.KIT),
    )

    /** The preset named [name], or null. */
    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }

    /** [name]'s instrument; fails loudly for an unknown name (demo and test songs use it). */
    fun instrument(name: String): SmsInstrument = named(name)?.instrument ?: throw IllegalArgumentException("No Master System preset \"$name\"")

    /** The presets of the kinds [layout] plays, in slot order for new songs. */
    fun forLayout(layout: SmsLayout): List<Preset> = ALL.filter {
        when (it.category) {
            Category.PSG, Category.NOISE -> true
            Category.FM -> layout.fm
            Category.KIT -> layout.drums
        }
    }

    /** Presets of instrument [kind] ([noise]: noise sounds first for the Noise channel). */
    fun forKind(kind: SmsInstrumentKind): List<Preset> = ALL.filter {
        when (kind) {
            SmsInstrumentKind.PSG -> it.category == Category.PSG || it.category == Category.NOISE
            SmsInstrumentKind.FM -> it.category == Category.FM
            SmsInstrumentKind.KIT -> it.category == Category.KIT
        }
    }

    /** A one-line reading of [ins]. */
    fun summary(ins: SmsInstrument): String = when (ins) {
        is SmsPsgInstrument -> listOfNotNull(
            "PSG",
            envelopeText("volume", ins.volume),
            envelopeText("arpeggio", ins.arpeggio),
            envelopeText("pitch", ins.pitch),
            if (ins.noise.values.isEmpty()) null else "noise " + SmsRules.noiseText(ins.noise.values.first(), null).substringBefore(','),
        ).joinToString(" · ")
        is SmsFmInstrument -> listOfNotNull(
            "FM",
            if (ins.isCustom) "custom voice (×${multText(ins.custom.modulator.mult)}:×${multText(ins.custom.carrier.mult)}, fb ${ins.custom.feedback})"
            else "${OpllPatch.PATCH_NAMES[ins.patch.coerceIn(1, 15)]} (built-in ${ins.patch})",
            envelopeText("volume", ins.volume),
            envelopeText("arpeggio", ins.arpeggio),
        ).joinToString(" · ")
        is SmsDrumKit -> if (!ins.fixed) "Drum kit · pitched by the drum notes"
            else "Drum kit · fixed: kick ${name(ins.kick)}, snare/hat ${name(ins.snareHat)}, tom/cymbal ${name(ins.tomCymbal)}"
    }

    private fun envelopeText(what: String, e: NesEnvelope): String? = if (e.values.isEmpty()) null else
        "$what ${e.values.size}" + (if (e.loop >= 0) " loop" else "") + (if (e.release >= 0) " rel" else "")

    private fun name(note: Int) = Songs.noteName(note, 0)

    /** The YM2413's multiplier [mult] as it plays: ½, 1-10, 10, 12, 12, 15, 15. */
    fun multText(mult: Int): String = when (mult.coerceIn(0, 15)) {
        0 -> "½"
        11 -> "10"
        13 -> "12"
        14, 15 -> "15"
        else -> mult.toString()
    }
}
