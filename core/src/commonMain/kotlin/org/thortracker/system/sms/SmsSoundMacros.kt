package org.thortracker.system.sms

import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.panel.EnvelopeContour

internal enum class SmsSoundControl(val label: String, val maximum: Int, val hint: String) {
    TOUCH("Contour", 2, "Volume recipe only; custom programs remain Custom"),
    CHORD("Chord", 4, "Explicit looping arpeggio; played note is the root"),
    GRAIN("Grain", 4, "Pitched noise borrows Square 3; pitch macros use the same slot"),
    VOICE("Voice", 15, "Built-in voices are fixed; Custom recalls your stored patch"),
    BRIGHTNESS("Brightness", 63, "Custom voice only: modulation amount, not output volume"),
    HARMONICS("Harmonics", 4, "Custom voice only: modulator harmonics"),
    EDGE("Edge", 7, "Custom voice only: feedback texture"),
    CONTOUR("Contour", 3, "Custom voice only: carrier envelope, not modulation"),
    TRACKING("Tuning", 1, "One drum mode shared by the five rhythm voices"),
    KICK("Kick", 95, "Fixed bass-drum pitch; other drums remain unchanged"),
    SNARE("Snare + hat", 95, "These two drums share a pitch register"),
    TOM("Tom + cymbal", 95, "These two drums share a pitch register"),
}

internal object SmsSoundMacros {
    val PSG = listOf(SmsSoundControl.TOUCH, SmsSoundControl.CHORD, SmsSoundControl.GRAIN)
    val FM = listOf(SmsSoundControl.VOICE, SmsSoundControl.BRIGHTNESS, SmsSoundControl.HARMONICS, SmsSoundControl.EDGE, SmsSoundControl.CONTOUR)
    val KIT = listOf(SmsSoundControl.TRACKING, SmsSoundControl.KICK, SmsSoundControl.SNARE, SmsSoundControl.TOM)
    fun controls(kind: SmsInstrumentKind): List<SmsSoundControl> = when (kind) {
        SmsInstrumentKind.PSG -> PSG
        SmsInstrumentKind.FM -> FM
        SmsInstrumentKind.KIT -> KIT
    }
    private val touchNames = arrayOf("Held", "Pluck", "Bloom", "Short")
    private val chordNames = arrayOf("Single", "Major", "Minor", "Open fifth", "Octaves")
    private val chords = arrayOf(NesEnvelope(), NesEnvelope(values = listOf(0, 4, 7), loop = 0),
        NesEnvelope(values = listOf(0, 3, 7), loop = 0), NesEnvelope(values = listOf(0, 7), loop = 0), NesEnvelope(values = listOf(0, 12), loop = 0))
    private val grainNames = arrayOf("Tone / inherit", "Periodic", "White", "Pitched periodic", "Pitched white")
    private val materials = intArrayOf(1, 2, 3, 5, 7)
    private val materialNames = arrayOf("Round", "Hollow", "Reed", "Metal", "Glass")
    private val contours = arrayOf(
        intArrayOf(15, 0, 0, 7, 1), intArrayOf(15, 8, 6, 7, 0),
        intArrayOf(6, 2, 2, 5, 1), intArrayOf(15, 12, 10, 12, 0),
    )
    fun enabled(c: SmsSoundControl, ins: SmsInstrument): Boolean = when (c) {
        SmsSoundControl.TOUCH, SmsSoundControl.CHORD -> ins is SmsPsgInstrument
        SmsSoundControl.GRAIN -> ins is SmsPsgInstrument && ins.pitch.values.isEmpty()
        SmsSoundControl.VOICE -> ins is SmsFmInstrument
        SmsSoundControl.BRIGHTNESS, SmsSoundControl.HARMONICS, SmsSoundControl.EDGE, SmsSoundControl.CONTOUR -> ins is SmsFmInstrument && ins.isCustom
        SmsSoundControl.TRACKING -> ins is SmsDrumKit
        else -> ins is SmsDrumKit && ins.fixed
    }
    fun value(c: SmsSoundControl, ins: SmsInstrument): Int? = when (ins) {
        is SmsPsgInstrument -> when (c) {
            SmsSoundControl.TOUCH -> EnvelopeContour.read(ins.volume)?.shape?.ordinal
            SmsSoundControl.CHORD -> chords.indexOf(ins.arpeggio)
            SmsSoundControl.GRAIN -> when {
                ins.noise.values.isEmpty() -> 0
                ins.noise.values.size == 1 && ins.noise.loop == -1 && ins.noise.release == -1 -> ins.noise.values[0] + 1
                else -> null
            }
            else -> null
        }
        is SmsFmInstrument -> when (c) {
            SmsSoundControl.VOICE -> ins.patch
            SmsSoundControl.BRIGHTNESS -> 63 - ins.custom.level
            SmsSoundControl.HARMONICS -> materials.indexOf(ins.custom.modulator.mult)
            SmsSoundControl.EDGE -> ins.custom.feedback
            SmsSoundControl.CONTOUR -> contours.indexOfFirst { r -> ins.custom.carrier.let { it.ar == r[0] && it.dr == r[1] && it.sl == r[2] && it.rr == r[3] && it.sustained == (r[4] == 1) } }
            else -> null
        }
        is SmsDrumKit -> when (c) {
            SmsSoundControl.TRACKING -> if (ins.fixed) 1 else 0
            SmsSoundControl.KICK -> ins.kick
            SmsSoundControl.SNARE -> ins.snareHat
            SmsSoundControl.TOM -> ins.tomCymbal
            else -> null
        }
    }?.takeIf { it >= 0 }
    fun label(c: SmsSoundControl, n: Int): String = when (c) {
        SmsSoundControl.TOUCH -> EnvelopeContour.Shape.entries[n].label
        SmsSoundControl.CONTOUR -> touchNames[n]
        SmsSoundControl.CHORD -> chordNames[n]
        SmsSoundControl.GRAIN -> grainNames[n]
        SmsSoundControl.VOICE -> OpllPatch.PATCH_NAMES[n]
        SmsSoundControl.BRIGHTNESS -> "${n * 100 / 63}%"
        SmsSoundControl.HARMONICS -> materialNames[n]
        SmsSoundControl.EDGE -> "$n / 7"
        SmsSoundControl.TRACKING -> if (n == 0) "Played notes" else "Fixed kit"
        else -> "${arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[n % 12]}${n / 12}"
    }
    fun apply(c: SmsSoundControl, ins: SmsInstrument, requested: Int): SmsInstrument {
        val n = requested.coerceIn(0, c.maximum)
        if (!enabled(c, ins) || value(c, ins) == n) return ins
        return when (ins) {
            is SmsPsgInstrument -> when (c) {
                SmsSoundControl.TOUCH -> ins.copy(volume = EnvelopeContour.choose(ins.volume, EnvelopeContour.Shape.entries[n]))
                SmsSoundControl.CHORD -> ins.copy(arpeggio = chords[n])
                SmsSoundControl.GRAIN -> ins.copy(noise = if (n == 0) NesEnvelope() else NesEnvelope(values = listOf(n - 1)))
                else -> ins
            }
            is SmsFmInstrument -> when (c) {
                // Do not use Advanced's copy-ROM-to-custom command: recall preserves the user's stored custom patch.
                SmsSoundControl.VOICE -> ins.copy(patch = n)
                SmsSoundControl.BRIGHTNESS -> ins.copy(custom = ins.custom.copy(level = 63 - n))
                SmsSoundControl.HARMONICS -> ins.copy(custom = ins.custom.copy(modulator = ins.custom.modulator.copy(mult = materials[n])))
                SmsSoundControl.EDGE -> ins.copy(custom = ins.custom.copy(feedback = n))
                SmsSoundControl.CONTOUR -> contours[n].let { r -> ins.copy(custom = ins.custom.copy(carrier = ins.custom.carrier.copy(ar = r[0], dr = r[1], sl = r[2], rr = r[3], sustained = r[4] == 1))) }
                else -> ins
            }
            is SmsDrumKit -> when (c) {
                SmsSoundControl.TRACKING -> ins.copy(fixed = n == 1)
                SmsSoundControl.KICK -> ins.copy(kick = n)
                SmsSoundControl.SNARE -> ins.copy(snareHat = n)
                SmsSoundControl.TOM -> ins.copy(tomCymbal = n)
                else -> ins
            }
        }
    }
    fun step(c: SmsSoundControl, ins: SmsInstrument, delta: Int): SmsInstrument =
        if (delta == 0) ins else apply(c, ins, ((value(c, ins) ?: if (delta > 0) -1 else c.maximum + 1) + delta).coerceIn(0, c.maximum))
}
