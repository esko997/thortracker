package org.thortracker.system.gb

import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.editor.EditorSession
import org.thortracker.model.NumberText

/** One editable GB instrument parameter with its legal range and readable value text. */
class SoundParam(
    val label: String,
    val min: Int,
    val max: Int,
    val coarse: Int,
    val help: String,
    val get: (Instrument) -> Int,
    val set: (Instrument, Int) -> Instrument,
    val format: (Instrument, Int) -> String = { _, v -> v.toString() },
) {
    fun adjust(i: Instrument, delta: Int): Instrument = set(i, (get(i) + delta).coerceIn(min, max))
}

object SoundParams {
    private val duty = SoundParam("Duty", 0, 3, 1, "Pulse width: thin 12.5% to hollow 75%",
        { it.duty }, { i, v -> i.copy(duty = v) }, { _, v -> arrayOf("12.5%", "25%", "50%", "75%")[v] })

    private val volume = SoundParam("Volume", 0, 15, 4, "Starting volume of the envelope",
        { it.volume }, { i, v -> i.copy(volume = v) })

    private val envDir = SoundParam("Envelope", 0, 1, 1, "Envelope direction: fade out or swell in",
        { it.envelopeDirection }, { i, v -> i.copy(envelopeDirection = v) }, { _, v -> if (v == 0) "Fade" else "Swell" })

    private val envPace = SoundParam("Env pace", 0, 7, 2, "Steps every pace/64 s; 0 holds the volume",
        { it.envelopePace }, { i, v -> i.copy(envelopePace = v) },
        { _, v -> if (v == 0) "Hold" else "$v (${NumberText.fixed(v * 1000.0 / 64, 0)} ms/step)" })

    private val lengthOn = SoundParam("Length cut", 0, 1, 1, "Silence the note after the length timer",
        { if (it.lengthEnabled) 1 else 0 }, { i, v -> i.copy(lengthEnabled = v == 1) }, { _, v -> if (v == 1) "On" else "Off" })

    private val length64 = SoundParam("Length", 0, 63, 8, "Timer value; higher is shorter",
        { it.length }, { i, v -> i.copy(length = v) },
        { _, v -> "$v (${NumberText.fixed((64 - v) * 1000.0 / 256, 0)} ms)" })

    private val length256 = SoundParam("Length", 0, 255, 16, "Timer value; higher is shorter",
        { it.length }, { i, v -> i.copy(length = v) },
        { _, v -> "$v (${NumberText.fixed((256 - v) * 1000.0 / 256, 0)} ms)" })

    private val sweepTime = SoundParam("Sweep time", 0, 7, 2, "Pitch sweep step every time/128 s; 0 off (pulse 1 only)",
        { it.sweepTime }, { i, v -> i.copy(sweepTime = v) }, { _, v -> if (v == 0) "Off" else "$v (${NumberText.fixed(v * 1000.0 / 128, 1)} ms)" })

    private val sweepDir = SoundParam("Sweep dir", 0, 1, 1, "Sweep pitch downwards or upwards",
        { it.sweepDirection }, { i, v -> i.copy(sweepDirection = v) }, { _, v -> if (v == 0) "Down" else "Up" })

    private val sweepShift = SoundParam("Sweep shift", 0, 7, 2, "Size of each sweep step; 0 off",
        { it.sweepShift }, { i, v -> i.copy(sweepShift = v) })

    private val wave = SoundParam("Wave", 0, 15, 4, "Waveform played by this sound",
        { it.wave }, { i, v -> i.copy(wave = v) }, { _, v -> "Wave ${EditorSession.hex1(v)}" })

    private val waveVolume = SoundParam("Output", 0, 3, 1, "Wave channel output level",
        { outputRank(it.waveVolume) }, { i, v -> i.copy(waveVolume = OUTPUT_ORDER[v]) },
        { _, v -> arrayOf("Mute", "25%", "50%", "100%")[v] })

    private val noiseWidth = SoundParam("Noise", 0, 1, 1, "Long 15-bit hiss or short 7-bit metallic tone",
        { it.noiseWidth }, { i, v -> i.copy(noiseWidth = v) }, { _, v -> if (v == 0) "Long (15-bit)" else "Short (7-bit)" })

    /** Raw waveVolume values in ascending loudness: 0 mute, 3 quarter, 2 half, 1 full. */
    private val OUTPUT_ORDER = intArrayOf(0, 3, 2, 1)
    private fun outputRank(raw: Int) = OUTPUT_ORDER.indexOf(raw).coerceAtLeast(0)

    private val pulseParams = listOf(duty, volume, envDir, envPace, lengthOn, length64, sweepTime, sweepDir, sweepShift)
    private val waveParams = listOf(wave, waveVolume, lengthOn, length256)
    private val noiseParams = listOf(noiseWidth, volume, envDir, envPace, lengthOn, length64)

    fun forKind(kind: InstrumentKind): List<SoundParam> = when (kind) {
        InstrumentKind.PULSE -> pulseParams
        InstrumentKind.WAVE -> waveParams
        InstrumentKind.NOISE -> noiseParams
    }

    /** One-line reading of a sound's settings, e.g. "Duty 25% · Vol 15 fade 1 · Length off". */
    fun summary(i: Instrument): String {
        val env = when {
            i.envelopePace == 0 -> "Vol ${i.volume} hold"
            i.envelopeDirection == 0 -> "Vol ${i.volume} fade ${i.envelopePace}"
            else -> "Vol ${i.volume} swell ${i.envelopePace}"
        }
        val length = if (i.lengthEnabled) "Length ${i.length}" else "Length off"
        return when (i.kind) {
            InstrumentKind.PULSE -> buildString {
                append("Duty ").append(duty.format(i, i.duty)).append(" · ").append(env).append(" · ").append(length)
                if (i.sweepTime != 0 && i.sweepShift != 0) {
                    append(" · Sweep ").append(if (i.sweepDirection == 0) "down " else "up ").append(i.sweepTime).append('/').append(i.sweepShift)
                }
            }
            InstrumentKind.WAVE -> "Wave ${EditorSession.hex1(i.wave)} · ${waveVolume.format(i, waveVolume.get(i))} · $length"
            InstrumentKind.NOISE -> "${if (i.noiseWidth == 0) "Long noise" else "Short noise"} · $env · $length"
        }
    }
}
