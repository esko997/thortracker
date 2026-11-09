package org.thortracker.system.tia

/**
 * Starting points for Atari 2600 songs: the sounds a new song has in slots 01.. ([ALL]; the instrument editor's
 * Preset control loads any of them into a slot). Each is honest about its waveform: [Category.TONAL] presets play
 * notes at the nearest divider of their waveform (its span is in the instrument view's pitch guide); [Category.RAW]
 * presets (noise and drums) take the divider straight from the note (C-0 = AUDF 31, one step brighter per semitone).
 */
object TiaPresets {
    enum class Category { TONAL, RAW }

    /** A starting sound: [name] is its [instrument]'s name. */
    data class Preset(val name: String, val category: Category, val instrument: TiaInstrument)

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = TiaEnvelope(values.toList(), loop, release)

    private fun tonal(name: String, waveform: Int, volume: TiaEnvelope = TiaEnvelope.NONE, arpeggio: TiaEnvelope = TiaEnvelope.NONE,
                      pitch: TiaEnvelope = TiaEnvelope.NONE, relative: Boolean = false) =
        Preset(name, Category.TONAL, TiaInstrument(name, waveform, raw = false, relativePitch = relative, volume = volume,
            arpeggio = arpeggio, pitch = pitch))

    private fun raw(name: String, waveform: Int, volume: TiaEnvelope, arpeggio: TiaEnvelope = TiaEnvelope.NONE,
                    waveforms: TiaEnvelope = TiaEnvelope.NONE) =
        Preset(name, Category.RAW, TiaInstrument(name, waveform, raw = true, volume = volume, arpeggio = arpeggio, waveforms = waveforms))

    val ALL: List<Preset> = listOf(
        // 01 The div 6 square (AUDC C): the TIA's melodic voice, E-3..D#8; held, short fade after OFF.
        tonal("Square div 6", 0xC, env(15, 14, 13, 12, 9, 6, 3, 0, release = 4)),
        // 02 The div 2 square (AUDC 4): bright, B-4 and up, coarse steps in the top octaves.
        tonal("Square div 2", 0x4, env(13, 12, 11, 10, 7, 4, 2, 0, release = 4)),
        // 03 A plucked div 6 square that dies by itself.
        tonal("Pluck div 6", 0xD, env(15, 13, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        // 04 The 18/13 div 31 tone (AUDC 6): the bass, C-1..B-5; dividers far apart above C-4.
        tonal("Bass div 31", 0x6, env(15, 15, 14, 13, 12, 8, 4, 0, release = 5)),
        // 05 Div 93 (AUDC E): very low, C-0..E-4 (its lowest dividers lie below C-0).
        tonal("Low div 93", 0xE, env(15, 14, 13, 12, 6, 0, release = 4)),
        // 06 The 4-bit poly (AUDC 1): a 15-step buzz, C-2..C-7.
        tonal("Buzz 4-bit", 0x1, env(12, 12, 11, 11, 10, 6, 2, 0, release = 5)),
        // 07 5-bit poly → div 2 (AUDC 7): a reedy, uneven lead with div 31's span.
        tonal("Poly lead 7", 0x7, env(14, 13, 12, 11, 7, 3, 0, release = 4)),
        // 08 5-bit poly ÷ 6 (AUDC F): low and gritty, div 93's span.
        tonal("Grit F", 0xF, env(15, 13, 12, 11, 6, 0, release = 4)),
        // 09 5-bit → 4-bit poly (AUDC 3), raw: a rumble whose rate the note picks.
        raw("Rumble 3", 0x3, env(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        // 10 Div 15 → 4-bit poly (AUDC 2), raw: an engine drone, held.
        raw("Engine 2", 0x2, env(12, 6, 0, release = 1)),
        // 11 Kick: a short white-noise click, then the div 93 tone falling (raw: the arpeggio raises AUDF).
        raw("Kick", 0xE, env(15, 15, 13, 11, 9, 7, 5, 3, 1, 0), arpeggio = env(0, 0, -2, -4, -6, -8, -10, -12),
            waveforms = env(0x8, 0xE)),
        // 12 Snare: white noise (AUDC 8), a bright attack then darker.
        raw("Snare", 0x8, env(15, 13, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0), arpeggio = env(0, -3, -5, -6, -7)),
        // 13 Closed hat: a short burst of white noise at a high rate (play high notes: AUDF 0-3).
        raw("Closed hat", 0x8, env(9, 6, 3, 1, 0)),
        // 14 Open hat: the same noise, longer.
        raw("Open hat", 0x8, env(10, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 0)),
        // 15 AUDC 0 is a constant level: the volume envelope's steps are the sound (a click at the frame rate).
        tonal("DC click", 0x0, env(15, 8, 0)),
        // 16 Zap: a raw div 2 square whose divider climbs every tick, a rising laser.
        raw("Zap", 0x4, env(15, 14, 13, 12, 11, 10, 8, 6, 4, 2, 0), arpeggio = env(0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30)),
    )

    /** The preset named [name], or null. */
    fun named(name: String): Preset? = ALL.firstOrNull { it.name == name }
}
