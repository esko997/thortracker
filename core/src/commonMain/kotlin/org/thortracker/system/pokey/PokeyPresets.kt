package org.thortracker.system.pokey

import org.thortracker.system.nes.NesEnvelope

/** A named starting sound for a POKEY instrument slot. */
class PokeyPreset(val name: String, val instrument: PokeyInstrument)

/**
 * Starting sounds for POKEY instruments: a new song has [ALL] in slots 01.. (slot 01 plays on every channel) and the
 * instrument editor's Preset control loads any of them into a slot. Every kind is represented: 8-bit pure and
 * distorted tones, noise drums, volume only, 16-bit, high-pass and difference ("saw") sounds and the silent timer two-tone
 * sync needs. Sample sounds are not in [ALL]: a blank song has no samples, so they come from [drumKit], which the
 * editor's kit import uses after adding [PokeySamples.starterKit] to the song.
 *
 * Volume envelopes end in a release (OFF fades them) or run down to 0 by themselves (drums).
 */
object PokeyPresets {
    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    private fun preset(name: String, ins: PokeyInstrument) = PokeyPreset(name, ins.copy(name = name))

    val ALL: List<PokeyPreset> = listOf(
        preset("Pure lead", PokeyInstrument(
            volume = env(12, 15, 14, 13, 12, 12, 10, 8, 6, 4, 2, 0, loop = 5, release = 6))),
        preset("Soft square", PokeyInstrument(
            volume = env(10, 9, 8, 8, 6, 4, 2, 0, loop = 3, release = 4))),
        // A held major triad on one channel: the classic Atari chord.
        preset("Major arp", PokeyInstrument(
            volume = env(10, 6, 3, 0, loop = 0, release = 1),
            arpeggio = env(0, 0, 4, 4, 7, 7, loop = 0))),
        preset("Minor arp", PokeyInstrument(
            volume = env(10, 6, 3, 0, loop = 0, release = 1),
            arpeggio = env(0, 0, 3, 3, 7, 7, loop = 0))),
        // Distortion C on the 64 kHz clock: the buzzing Atari bass (15-step 4-bit pattern).
        preset("Dist C bass", PokeyInstrument(
            volume = env(15, 14, 13, 12, 11, 10, 7, 4, 0, loop = 5, release = 6),
            distortion = env(6))),
        preset("16-bit bass", PokeyInstrument(
            kind = PokeyKind.JOINED, fast = true,
            volume = env(15, 13, 12, 11, 11, 8, 5, 2, 0, loop = 4, release = 5))),
        // A pulse wave whose width drifts: the clock channel runs one step off the tone.
        preset("Phasing lead", PokeyInstrument(
            kind = PokeyKind.FILTERED, filterOffset = 1,
            volume = env(13, 14, 13, 12, 9, 6, 3, 0, loop = 3, release = 4))),
        // The "POKEY saw": the note is the difference of the two counters' rates (POKEY 1 with POKEY 3, 1.79 MHz).
        preset("POKEY saw", PokeyInstrument(
            kind = PokeyKind.FILTERED, fast = true, difference = true, filterOffset = 1,
            volume = env(15, 14, 13, 10, 6, 3, 0, loop = 2, release = 3))),
        preset("16-bit saw", PokeyInstrument(
            kind = PokeyKind.JOINED_FILTERED, fast = true, difference = true, filterOffset = 4,
            volume = env(15, 14, 13, 13, 9, 5, 2, 0, loop = 3, release = 4))),
        // Pure for one tick, then 17-bit noise: the note picks the noise rate.
        preset("Noise snare", PokeyInstrument(
            volume = env(15, 12, 10, 8, 6, 5, 4, 3, 2, 1, 0),
            distortion = env(5, 4))),
        preset("Noise hat", PokeyInstrument(
            volume = env(11, 7, 4, 2, 1, 0),
            distortion = env(4))),
        preset("Tone kick", PokeyInstrument(
            volume = env(15, 14, 12, 10, 7, 4, 2, 0),
            pitch = env(0, -48, -48, -40, -32, -24, -16, -8), pitchRelative = true)),
        // Volume only: the level is a DC step on the DAC, a thump through the output's AC coupling.
        preset("Thump", PokeyInstrument(
            volume = env(15, 11, 7, 4, 2, 1, 0),
            distortion = env(PokeyDistortion.VOLUME_ONLY))),
        // On POKEY 1 (1.79 MHz) under W02: POKEY 2's timer restarts it, so the note sets the timbre, POKEY 2 the pitch.
        preset("Sync lead", PokeyInstrument(
            fast = true,
            volume = env(14, 13, 12, 12, 8, 4, 0, loop = 3, release = 4))),
        // Silent: its timer is the pitch two-tone sync (W02) restarts POKEY 1 with, on POKEY 2.
        preset("Sync clock", PokeyInstrument(volume = env(0))),
    )

    /** The preset named [name], or null. */
    fun named(name: String): PokeyPreset? = ALL.firstOrNull { it.name == name }

    /** [name]'s instrument; fails loudly for an unknown name (the demo and tests use it). */
    fun instrument(name: String): PokeyInstrument = named(name)?.instrument ?: throw IllegalArgumentException("No POKEY preset \"$name\"")

    /** Names of [drumKit]'s sounds, in [PokeySamples.starterKit] order. */
    val DRUM_KIT_NAMES = listOf("Digi kick", "Digi snare", "Digi hat", "Digi tom")

    /**
     * Sample sounds playing [PokeySamples.starterKit]'s drums, whose samples start at index [firstSample] of the song's
     * samples: one-shot, at the kit's rate on C-4 (the note bends them like a tape-speed change).
     */
    fun drumKit(firstSample: Int): List<PokeyPreset> = DRUM_KIT_NAMES.mapIndexed { k, name ->
        preset(name, PokeyInstrument(
            kind = PokeyKind.SAMPLE, sample = firstSample + k, root = PokeyInstrument.DEFAULT_ROOT,
            rate = PokeySamples.KIT_RATE, loop = -1))
    }
}
