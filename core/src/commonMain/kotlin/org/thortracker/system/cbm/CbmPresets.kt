package org.thortracker.system.cbm

/**
 * Starting sounds for VIC-20 and C16/Plus/4 songs (original settings, written for these chips): a new song has its
 * chip's presets in slots 01.. ([forChip]); the instrument editor's Preset control loads any of them into a slot.
 *
 * Neither chip has a volume per voice, so the presets shape single voices with wave envelopes (a 0 step gates the
 * voice off: plucks, staccato, drums) and pitch or arpeggio envelopes; only the [master] ones carry a volume
 * envelope, which takes the chip's one master volume while their note sounds (every voice follows it). On the VIC
 * every sound plays noise on the Noise lane, where the note picks the rate (higher is brighter) and arpeggios step it:
 * the noise presets are voiced for that lane. On the TED the noise presets play voice 2's noise (Voice 2 only).
 */
object CbmPresets {
    /** A starting sound: [name] is its [instrument]'s name; [master] when its volume envelope takes the master volume. */
    data class Preset(val name: String, val instrument: CbmInstrument) {
        val master: Boolean get() = !instrument.volume.isEmpty
    }

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = CbmEnvelope(values.toList(), loop, release)

    /** [n] ticks of [value], then one of [last]: a gate for wave envelopes. */
    private fun gate(n: Int, value: Int = 1, last: Int = 0) = CbmEnvelope(List(n) { value } + last)

    private fun p(name: String, wave: Int = 1, volume: CbmEnvelope = CbmEnvelope.NONE, arpeggio: CbmEnvelope = CbmEnvelope.NONE,
                  pitch: CbmEnvelope = CbmEnvelope.NONE, relative: Boolean = false, waves: CbmEnvelope = CbmEnvelope.NONE) =
        Preset(name, CbmInstrument(name, CbmKind.TONE, wave, volume, arpeggio, pitch, relative, waves))

    /** Sounds both chips play alike on their square voices. */
    private val SHARED: List<Preset> = listOf(
        p("Square"),
        // Six ticks of tone, then the voice gates off: a plucked note without touching the shared volume.
        p("Pluck", waves = gate(6)),
        p("Staccato", waves = gate(2)),
        p("Octave arp", arpeggio = env(0, 0, 12, 12, loop = 0)),
        p("Major arp", arpeggio = env(0, 4, 7, loop = 0)),
        p("Minor arp", arpeggio = env(0, 3, 7, loop = 0)),
        // A delayed vibrato: straight for 8 ticks, then ±1/4 semitone.
        p("Vibrato lead", pitch = env(0, 0, 0, 0, 0, 0, 0, 0, 8, 16, 8, 0, -8, -16, -8, 0, loop = 8)),
        // Falls 5/8 semitone per tick for 12 ticks, then gates off.
        p("Zap", pitch = env(-40, loop = 0), relative = true, waves = gate(12)),
        // A bass that dips a quarter tone per tick after its attack, gated: a synth-tom-like thump.
        p("Drop bass", pitch = env(0, 0, -16, loop = 2), relative = true, waves = gate(10)),
    )

    /** The VIC's presets: the shared ones, master-volume shapes (0..15) and noise drums for the Noise lane. */
    val VIC: List<Preset> = SHARED + listOf(
        // Every voice swells in while this note sounds (the chip's one volume).
        p("Swell", volume = env(3, 5, 7, 9, 11, 13, 15)),
        // Full while held; after OFF the master volume fades out over 8 ticks, taking every voice with it.
        p("Release fade", volume = env(15, 13, 11, 9, 7, 5, 3, 1, 0, release = 1)),
        // Noise lane drums: the note picks the rate (C-8 and up are bright, C-3 and below rumble).
        p("Noise hat", waves = gate(2)),
        p("Noise snare", arpeggio = env(0, -6, -10, -14, -18), waves = gate(6)),
        p("Noise kick", arpeggio = env(0, -24, -40, -56), waves = gate(4)),
        p("Noise crash", arpeggio = env(0, -2, -4, -6, -8, -10, -12, -14), waves = gate(24)),
    )

    /** The TED's presets: the shared ones, master-volume shapes (0..8), a square kick and Voice 2 noise. */
    val TED: List<Preset> = SHARED + listOf(
        p("Swell", volume = env(2, 3, 4, 5, 6, 7, 8)),
        p("Release fade", volume = env(8, 7, 6, 5, 4, 3, 2, 1, 0, release = 1)),
        // A square whose pitch drops two semitones per tick, gated after five ticks: a kick on either voice.
        p("Square kick", pitch = env(0, -128, loop = 1), relative = true, waves = gate(5)),
        // Voice 2's noise generator (Voice 2 only): wave 2, or a wave envelope that switches to it.
        p("Noise", wave = 2),
        p("Noise hat", wave = 2, waves = gate(2, value = 2)),
        // A square click into noise, the noise rate falling.
        p("Noise snare", wave = 2, arpeggio = env(0, -5, -9, -12, -14, -16), waves = env(1, 2, 2, 2, 2, 2, 0)),
        p("Noise crash", wave = 2, arpeggio = env(0, -1, -2, -3, -4, -5, -6, -7), waves = gate(30, value = 2)),
    )

    /** The presets of [chip], in slot order for a new song. */
    fun forChip(chip: CbmChip): List<Preset> = if (chip == CbmChip.VIC) VIC else TED

    /** [chip]'s preset named [name], or null. */
    fun named(chip: CbmChip, name: String): Preset? = forChip(chip).firstOrNull { it.name == name }
}
