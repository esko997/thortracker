package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

/**
 * Starting points for VSU songs: instrument presets (a new song has them in slots 01-16 and the instrument editor's
 * Preset row loads any of them into a slot), wave shapes for the five shared waves and modulation tables for Wave 5.
 * [Preset.lane] is the lane a preset is voiced for (Wave 5 sounds use its sweep or modulation, noise drums its taps)
 * and [Preset.note] the note drums were voiced at (on Noise the note picks the shift register's rate).
 */
object VsuPresets {
    enum class Category(val label: String) { MELODIC("Melodic"), WAVE5("Wave 5"), NOISE("Noise drum") }

    data class Preset(val instrument: VsuInstrument, val category: Category, val lane: Int, val note: Int? = null) {
        val name: String get() = instrument.name
    }

    data class WavePreset(val name: String, val samples: List<Int>)

    data class ModPreset(val name: String, val values: List<Int>)

    private const val W1 = 0
    private const val W5 = VsuPlayerImage.SWEEP_LANE
    private const val NOISE = VsuPlayerImage.NOISE_LANE

    // Waves of the default set, by index: 0 sine, 1 triangle, 2 saw, 3 square, 4 pulse 25 %.
    private const val SINE = 0
    private const val TRIANGLE = 1
    private const val SAW = 2
    private const val SQUARE = 3
    private const val PULSE = 4

    private fun melodic(ins: VsuInstrument) = Preset(ins, Category.MELODIC, W1)
    private fun wave5(ins: VsuInstrument, note: Int? = null) = Preset(ins, Category.WAVE5, W5, note)
    private fun noise(ins: VsuInstrument, note: Int) = Preset(ins, Category.NOISE, NOISE, note)

    /** Every instrument preset, melodic first, then Wave 5's sweep and modulation sounds, then noise drums. */
    val ALL: List<Preset> = listOf(
        // Decaying envelopes: one level step every (interval + 1) × 15.36 ms from the start level.
        melodic(VsuInstrument("Square lead", wave = SQUARE, level = 15, envelope = true, interval = 7)),
        melodic(VsuInstrument("Saw lead", wave = SAW, level = 14, envelope = true, interval = 6)),
        melodic(VsuInstrument("Flute", wave = SINE, level = 13)),
        melodic(VsuInstrument("Bass", wave = TRIANGLE, level = 15, envelope = true, interval = 5)),
        melodic(VsuInstrument("Pluck", wave = PULSE, level = 15, envelope = true, interval = 0)),
        // Growing envelope: swells from level 3 to 15 in about 0.9 s and holds.
        melodic(VsuInstrument("Swell pad", wave = SINE, level = 3, envelope = true, grow = true, interval = 4)),
        // Repeat reloads the start level after the envelope reaches 0: a 5 Hz amplitude saw.
        melodic(VsuInstrument("Organ tremolo", wave = SQUARE, level = 13, envelope = true, interval = 0, repeat = true)),
        // Wave 5 modulation: the shared table added to the frequency every 5.76 ms, repeating (≈5.4 Hz vibrato).
        wave5(VsuInstrument("Vibrato lead", wave = SAW, level = 15, envelope = true, interval = 7,
            freqMod = VsuFreqMod(on = true, modulation = true, repeat = true, interval = 6))),
        // Wave 5 sweep down: F loses a quarter every 1.92 ms, the pitch dives.
        wave5(VsuInstrument("Laser", wave = SQUARE, level = 15, envelope = true, interval = 1,
            freqMod = VsuFreqMod(on = true, interval = 2, shift = 2)), note = 84),
        // Wave 5 sweep down from the note: F halves every 7.68 ms; the hardware length cuts it after 123 ms.
        wave5(VsuInstrument("Sweep kick", wave = SINE, level = 15, envelope = true, interval = 0, autoLength = true, length = 31,
            freqMod = VsuFreqMod(on = true, slowClock = true, interval = 1, shift = 1)), note = 48),
        // Wave 5 sweep up: +1/16 every 53.8 ms until F passes 2047, where the hardware stops the channel.
        wave5(VsuInstrument("Riser", wave = SAW, level = 8, envelope = true, grow = true, interval = 3,
            freqMod = VsuFreqMod(on = true, slowClock = true, interval = 7, up = true, shift = 4)), note = 36),
        // The player's pitch sweep: from 7 semitones above the note to 5 below at 1.5 semitones per tick.
        melodic(VsuInstrument("Tom", wave = SINE, level = 15, envelope = true, interval = 1, sweep = VsuSweep(7, -5, 24))),
        // Noise: the tap picks the shift-register sequence, the note its rate, the hardware length the decay's end.
        noise(VsuInstrument("Noise kick", level = 15, envelope = true, interval = 0, autoLength = true, length = 20, noiseTap = 6), note = 24),
        noise(VsuInstrument("Noise snare", level = 15, envelope = true, interval = 1, autoLength = true, length = 31, noiseTap = 0), note = 72),
        noise(VsuInstrument("Noise hat", level = 11, envelope = true, interval = 0, autoLength = true, length = 5, noiseTap = 0), note = 84),
        noise(VsuInstrument("Metal hat", level = 12, envelope = true, interval = 0, autoLength = true, length = 12, noiseTap = 4), note = 79),
    )

    /** What a new song has in slots 01..: every preset. */
    val STARTERS: List<Preset> get() = ALL

    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }

    // ---- waves ------------------------------------------------------------------------------------------

    private const val N = VsuPlayerImage.WAVE_SAMPLES
    private const val MAX = VsuPlayerImage.SAMPLE_MAX

    /** A sum of sines [harmonics] (number to amplitude), scaled to fill 0..63. */
    private fun additive(name: String, harmonics: List<Pair<Int, Double>>): WavePreset {
        val raw = DoubleArray(N) { i -> harmonics.sumOf { (h, a) -> a * sin(2.0 * PI * h * i / N) } }
        val lo = raw.min()
        val hi = raw.max()
        return WavePreset(name, raw.map { round((it - lo) / (hi - lo) * MAX).toInt().coerceIn(0, MAX) })
    }

    private fun shape(name: String, f: (Int) -> Int) = WavePreset(name, List(N) { f(it).coerceIn(0, MAX) })

    val SINE_WAVE = shape("Sine") { i -> round(31.5 + 31.5 * sin(2.0 * PI * i / N)).toInt() }
    val TRIANGLE_WAVE = shape("Triangle") { i -> if (i < 16) round(i * MAX / 15.0).toInt() else round((31 - i) * MAX / 15.0).toInt() }
    val SAW_WAVE = shape("Saw") { i -> round(i * MAX / 31.0).toInt() }
    val SQUARE_WAVE = shape("Square") { i -> if (i < 16) MAX else 0 }
    val PULSE_WAVE = shape("Pulse 25%") { i -> if (i < 8) MAX else 0 }

    /** The five waves a new song starts with, in wave order 1-5. */
    val DEFAULT_WAVES: List<WavePreset> = listOf(SINE_WAVE, TRIANGLE_WAVE, SAW_WAVE, SQUARE_WAVE, PULSE_WAVE)

    /** Shapes the Wave view's preset tool steps through. */
    val WAVES: List<WavePreset> = DEFAULT_WAVES + listOf(
        shape("Pulse 12.5%") { i -> if (i < 4) MAX else 0 },
        additive("Organ", listOf(1 to 1.0, 2 to 0.6, 3 to 0.35, 4 to 0.25)),
        additive("Soft saw", listOf(1 to 1.0, 2 to 0.5, 3 to 0.33, 4 to 0.25, 5 to 0.2)),
        additive("Hollow", listOf(1 to 1.0, 3 to 0.45, 5 to 0.25, 7 to 0.12)),
        additive("Reed", listOf(1 to 0.6, 2 to 1.0, 3 to 0.5, 5 to 0.3)),
        shape("Half sine") { i -> if (i < 16) round(MAX * sin(PI * i / 16)).toInt() else 0 },
        shape("Buzz") { i -> if (i < 4) MAX else round((31 - i) * 40.0 / 27).toInt() },
    )

    // ---- modulation -------------------------------------------------------------------------------------

    private fun mod(name: String, f: (Int) -> Int) = ModPreset(name, List(VsuPlayerImage.MOD_VALUES) { f(it).coerceIn(-128, 127) })

    private fun sine(depth: Int): (Int) -> Int = { i -> round(depth * sin(2.0 * PI * i / VsuPlayerImage.MOD_VALUES)).toInt() }

    /** The modulation table a new song starts with: ±8 on the frequency register, a gentle vibrato at middle notes. */
    val DEFAULT_MODULATION = mod("Vibrato ±8", sine(8))

    /**
     * Tables the Wave view's modulation page steps through. Values add to the written frequency register F, so the same
     * table bends high notes further than low ones (one F step at A-4 is about 5 cents, at A-6 about 20).
     */
    val MODULATIONS: List<ModPreset> = listOf(
        mod("Vibrato ±3", sine(3)),
        DEFAULT_MODULATION,
        mod("Wide vibrato ±24", sine(24)),
        mod("Trill F +16") { i -> if (i < 16) 0 else 16 },
        mod("Wobble ±12") { i -> if (i < 16) 12 else -12 },
        mod("Bend up") { i -> i * 2 },
        mod("Drop") { i -> -i * 4 },
        mod("Zero") { 0 },
    )
}
