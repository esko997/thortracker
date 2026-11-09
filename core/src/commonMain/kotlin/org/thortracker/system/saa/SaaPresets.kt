package org.thortracker.system.saa

import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.saa.SaaInstrument.Companion.MIXER_BOTH
import org.thortracker.system.saa.SaaInstrument.Companion.MIXER_NOISE
import org.thortracker.system.saa.SaaInstrument.Companion.MIXER_OFF
import org.thortracker.system.saa.SaaInstrument.Companion.MIXER_TONE

/** A named starting point for an SAA1099 instrument slot. */
data class SaaPreset(val name: String, val instrument: SaaInstrument)

/**
 * The SAA1099 instrument presets, written for Thor Tracker (no game's data). A new song gets them in slots 01-12
 * ([SaaSound.blank]). Drums are instruments too: a tone kick swept down by a relative pitch envelope, a snare and a hat
 * on noise clock 0 (so drums on one lane triple share the noise generator without clashing), and a noise sweep on
 * clock 3, whose noise follows lane 1's (4's) tone: on lanes 1 and 4 its own pitch sweep moves the noise. The three
 * hardware-envelope presets play on lanes 3 and 6 only: the buzz bass's repeating decay is clocked by lane 2's (5's)
 * tone, three octaves below whatever lane 2 (5) plays, and with its mixer off the envelope itself is the waveform; the
 * slow swell and the mirror run on the player's external clock.
 */
object SaaPresets {
    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    val ALL: List<SaaPreset> = listOf(
        // Sustains at 12 while held; the release fades it out.
        SaaPreset("Lead", SaaInstrument("Lead",
            volume = env(15, 14, 13, 12, 10, 8, 6, 4, 2, 0, release = 4))),
        // Settles to 11 and stops at once on OFF.
        SaaPreset("Square bass", SaaInstrument("Square bass",
            volume = env(15, 14, 13, 12, 11))),
        // Fast decay to silence: plays staccato by itself.
        SaaPreset("Pluck", SaaInstrument("Pluck",
            volume = env(15, 12, 10, 8, 6, 5, 4, 3, 2, 1, 0))),
        // Slow swell with a gentle absolute wobble (±1/8 semitone), leaning left: a twin on another lane with 88F
        // leaning right makes a stereo pair.
        SaaPreset("Stereo pad", SaaInstrument("Stereo pad",
            volume = env(3, 5, 7, 9, 10, 11, 12, 12, 10, 8, 6, 4, 2, 1, 0, release = 7),
            pitch = env(0, 1, 2, 1, 0, -1, -2, -1, loop = 0),
            panLeft = 15, panRight = 8)),
        // Decaying repeats written into the volume envelope: an echo on one lane; 8xy on the following rows sweeps
        // each repeat across the stereo field.
        SaaPreset("Echo lead", SaaInstrument("Echo lead",
            volume = env(15, 13, 11, 9, 0, 0, 0, 9, 8, 6, 0, 0, 0, 6, 5, 4, 0, 0, 0, 4, 3, 2, 0, 0, 0, 2, 1, 1, 0))),
        // Tone swept down by a relative pitch envelope (sixteenths of a semitone per tick, accumulating), with a
        // one-tick noise click on clock 0.
        SaaPreset("Kick", SaaInstrument("Kick",
            volume = env(15, 15, 14, 12, 10, 8, 6, 4, 3, 2, 1, 0),
            pitch = env(-48, -40, -32, -24, -16, -12, -8),
            mixer = env(MIXER_BOTH, MIXER_TONE),
            pitchRelative = true, noise = 0)),
        // Tone and noise for the body, then noise alone.
        SaaPreset("Snare", SaaInstrument("Snare",
            volume = env(15, 14, 12, 10, 8, 7, 6, 5, 4, 3, 2, 1, 0),
            pitch = env(-8, -8, -4),
            mixer = env(MIXER_BOTH, MIXER_BOTH, MIXER_NOISE),
            pitchRelative = true, noise = 0)),
        SaaPreset("Hat", SaaInstrument("Hat",
            volume = env(12, 8, 5, 3, 1, 0),
            mixer = env(MIXER_NOISE), noise = 0)),
        // Noise at twice lane 1's (4's) tone: on lanes 1 and 4 the rising relative pitch sweeps the noise up.
        SaaPreset("Noise sweep", SaaInstrument("Noise sweep",
            volume = env(6, 9, 12, 15, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
            pitch = env(8, loop = 0),
            mixer = env(MIXER_NOISE),
            pitchRelative = true, noise = 3)),
        // Lane 3 or 6: mixer off, so the repeating decay itself is the waveform, a saw three octaves below lane 2's
        // (5's) note; holds until OFF, then fades.
        SaaPreset("Buzz bass", SaaInstrument("Buzz bass",
            volume = env(15, 15, 12, 9, 6, 3, 0, release = 2),
            mixer = env(MIXER_OFF),
            envelope = SaaHardwareEnvelope(shape = SaaHardwareEnvelope.REPETITIVE_DECAY, retrigger = true))),
        // Lane 3 or 6: the tone's level rises and falls on a repeating triangle, one step every 3 ticks.
        SaaPreset("Slow swell", SaaInstrument("Slow swell",
            volume = env(15, 12, 9, 6, 3, 0, release = 1),
            envelope = SaaHardwareEnvelope(shape = SaaHardwareEnvelope.REPETITIVE_TRIANGLE, external = true, rate = 3, retrigger = true))),
        // Lane 3 or 6: the right side plays the inverse of the left, so a fast repeating triangle pans the tone to and fro.
        SaaPreset("Mirror", SaaInstrument("Mirror",
            volume = env(14, 13, 12, 9, 6, 3, 0, release = 3),
            envelope = SaaHardwareEnvelope(shape = SaaHardwareEnvelope.REPETITIVE_TRIANGLE, invertRight = true, external = true,
                rate = 1, retrigger = true))),
    )

    private val BY_NAME = ALL.associateBy { it.name }

    fun named(name: String): SaaPreset? = BY_NAME[name]
}
