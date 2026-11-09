package org.thortracker.system.ay

import org.thortracker.system.nes.NesEnvelope

/**
 * Starting sounds for AY songs: a new song has them in slots 01.. and the instrument editor's Preset control loads any
 * of them into a slot. [Preset.note] is the note each was voiced at (the Listen note; for drums the note picks the
 * drum's pitch). Categories: tones, arpeggio chords, hardware-envelope sounds, drums and noise. Values are for 50/60 Hz
 * ticks; the hardware-envelope periods are chip clocks / 256 per ramp (manual) or follow the note (auto).
 */
object AyPresets {
    const val TONE = "Tone"
    const val ARPEGGIO = "Arpeggio"
    const val ENVELOPE = "Envelope"
    const val DRUM = "Drum"
    const val NOISE = "Noise"

    data class Preset(val name: String, val category: String, val instrument: AyInstrument, val note: Int)

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    private fun preset(
        name: String, category: String, note: Int,
        volume: NesEnvelope = NesEnvelope(), arpeggio: NesEnvelope = NesEnvelope(), pitch: NesEnvelope = NesEnvelope(),
        mixer: NesEnvelope = NesEnvelope(), noise: NesEnvelope = NesEnvelope(),
        envelope: AyEnvelopeSettings = AyEnvelopeSettings.OFF, sweep: AySweep = AySweep.NONE,
    ) = Preset(name, category, AyInstrument(name, volume, arpeggio, pitch, false, mixer, noise, envelope, sweep), note)

    /** A mixer envelope holding the noise gate only. */
    private val NOISE_ONLY = env(AyRules.MIXER_NOISE)

    val ALL: List<Preset> = listOf(
        // A held square: a short attack accent settling to 11, vibrato after a fifth of a second, a quick release tail.
        preset("Square lead", TONE, 57,
            volume = env(15, 14, 13, 12, 11, 11, 9, 7, 5, 3, 1, 0, loop = 5, release = 6),
            pitch = env(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 2, 1, 0, -1, -2, -3, -2, -1, 0, loop = 10)),
        preset("Pluck", TONE, 57,
            volume = env(15, 13, 11, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 0)),
        // A slow swell to 12, held, and a gentle fade after OFF.
        preset("Soft pad", TONE, 57,
            volume = env(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 10, 8, 6, 4, 2, 0, loop = 10, release = 11),
            pitch = env(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 1, 1, 0, -1, -1, -2, -1, -1, 0, loop = 12)),
        // The classic one-channel chords: root, third, fifth, two ticks each.
        preset("Arp major", ARPEGGIO, 57,
            volume = env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7),
            arpeggio = env(0, 0, 4, 4, 7, 7, loop = 0)),
        preset("Arp minor", ARPEGGIO, 57,
            volume = env(13, 13, 12, 12, 11, 11, 10, 7, 4, 0, loop = 6, release = 7),
            arpeggio = env(0, 0, 3, 3, 7, 7, loop = 0)),
        // The buzzer bass: no tone, the saw envelope itself at the note's pitch (auto J00).
        preset("Buzzer bass", ENVELOPE, 33,
            mixer = env(0),
            envelope = AyEnvelopeSettings(shape = 8, auto = true, octave = 0)),
        // A triangle envelope two octaves under the tone, locked to it (tone periods in steps of 8): a buzzy, beat-free lead.
        preset("Sync buzzer", ENVELOPE, 45,
            envelope = AyEnvelopeSettings(shape = 10, auto = true, octave = -2, sync = true)),
        // A tone under one falling ramp of about 0.6 s (period 1000), struck with an octave flick.
        preset("Bell", ENVELOPE, 69,
            arpeggio = env(12, 0),
            envelope = AyEnvelopeSettings(shape = 9, auto = false, period = 0x1000)),
        // A tone pulsed by a triangle envelope at about 6 Hz (period 0240).
        preset("Envelope tremolo", ENVELOPE, 57,
            envelope = AyEnvelopeSettings(shape = 14, auto = false, period = 0x0240)),
        // Tone and noise for two ticks (the click), then a tone sweeping down two and a half octaves.
        preset("Kick", DRUM, 24,
            volume = env(15, 15, 14, 13, 11, 9, 7, 5, 3, 2, 1, 0),
            mixer = env(3, 3, AyRules.MIXER_TONE),
            noise = env(1),
            sweep = AySweep(30, 0, 56)),
        // A darkening noise burst over a short swept tone body.
        preset("Snare", DRUM, 50,
            volume = env(15, 14, 13, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
            mixer = env(3, 3, 3, AyRules.MIXER_NOISE),
            noise = env(5, 6, 7, 8, 9, 10, 11, 12),
            sweep = AySweep(12, 0, 48)),
        preset("Closed hat", NOISE, 60,
            volume = env(13, 9, 6, 3, 1, 0),
            mixer = NOISE_ONLY,
            noise = env(1)),
        preset("Open hat", NOISE, 60,
            volume = env(13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 0),
            mixer = NOISE_ONLY,
            noise = env(2)),
        preset("Tom", DRUM, 43,
            volume = env(15, 14, 13, 12, 10, 9, 8, 6, 5, 4, 3, 2, 1, 0),
            sweep = AySweep(12, -2, 20)),
        // A riser: the noise period sweeping from 31 (dark) to 1 (bright) over 62 ticks while the volume swells.
        preset("Noise sweep", NOISE, 60,
            volume = env(6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15),
            mixer = NOISE_ONLY,
            noise = NesEnvelope((31 downTo 1).flatMap { listOf(it, it) })),
    )

    /** The preset named [name], or null. */
    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }

    /** [name]'s instrument; fails loudly for an unknown name (demo and test songs use it). */
    fun instrument(name: String): AyInstrument = named(name)?.instrument ?: throw IllegalArgumentException("No AY preset \"$name\"")
}
