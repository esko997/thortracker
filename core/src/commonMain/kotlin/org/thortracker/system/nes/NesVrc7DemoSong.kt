package org.thortracker.system.nes

import org.thortracker.model.Song
import org.thortracker.system.nes.NesDemoSong.PatternBuilder
import org.thortracker.system.nes.NesDemoSong.n

/**
 * The bundled NES + VRC7 demo: "Lantern Point", A minor, speed 6. Intro, theme and bridge, arranged 0-1-2-1 and looping
 * to order 1, in the manner of the VRC7's one Famicom soundtrack: a soft custom-patch lead (the chip's one programmable
 * patch, so only FM 1 plays it) with vibrato, a pickup glide and key-off releases, the built-in Bass guitar, Brass pads,
 * Piano arpeggio chords and Bell sparkles on the other five FM channels, and the 2A03 keeping an echo of the lead, a
 * triangle under the bridge, noise drums and the DPCM starter kit.
 */
internal object NesVrc7DemoSong {
    private const val ECHO = 1
    private const val BASS = 2
    private const val KICK = 3
    private const val SNARE = 4
    private const val HAT = 5
    private const val DRUM_KIT = 6
    private const val FM_LEAD = 7
    private const val FM_BASS = 8
    private const val FM_BRASS = 9
    private const val FM_PIANO = 10
    private const val FM_BELL = 11

    private const val PULSE1 = 0
    private const val TRIANGLE = NesRules.TRIANGLE
    private const val NOISE = NesRules.NOISE
    private const val DPCM = NesRules.DPCM
    private const val LEAD = NesRules.VRC7_FM1
    private const val FM_BASS_CH = NesRules.VRC7_FM1 + 1
    private const val PAD_LOW = NesRules.VRC7_FM1 + 2
    private const val PAD_HIGH = NesRules.VRC7_FM1 + 3
    private const val PIANO = NesRules.VRC7_FM1 + 4
    private const val BELL = NesRules.VRC7_FM1 + 5
    private const val CHANNELS = 11

    /** Theme chords Am F G E: the piano's arpeggio root and 0xy, the bass root, the two pad notes. */
    private val THEME_ROOTS = intArrayOf(n("A-4"), n("F-4"), n("G-4"), n("E-4"))
    private val THEME_ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)
    private val THEME_BASS = intArrayOf(n("A-2"), n("F-2"), n("G-2"), n("E-2"))
    private val PAD_LOWS = intArrayOf(n("A-4"), n("A-4"), n("B-4"), n("G#4"))
    private val PAD_HIGHS = intArrayOf(n("C-5"), n("C-5"), n("D-5"), n("B-4"))

    /** Bridge chords F G Am E. */
    private val BRIDGE_ROOTS = intArrayOf(n("F-4"), n("G-4"), n("A-4"), n("E-4"))
    private val BRIDGE_ARPS = intArrayOf(0x47, 0x47, 0x37, 0x47)
    private val BRIDGE_BASS = intArrayOf(n("F-2"), n("G-2"), n("A-2"), n("E-2"))

    fun build(): Song {
        val base = NesVrc7System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[ECHO - 1] = NesInstrument(name = "Echo 25%", volume = NesEnvelope(listOf(9, 8, 6, 5, 4, 3, 2, 1, 0)), duty = NesEnvelope(listOf(1)))
        bank[BASS - 1] = NesInstrument(name = "Triangle bass")
        bank[KICK - 1] = NesInstrument(
            name = "Kick",
            volume = NesEnvelope(listOf(15, 13, 10, 7, 4, 2, 0)),
            arpeggio = NesEnvelope(listOf(0, -1, -2, -3, -4, -5, -6)),
        )
        bank[SNARE - 1] = NesInstrument(name = "Snare", volume = NesEnvelope(listOf(14, 11, 9, 7, 6, 5, 4, 3, 2, 1, 0)), arpeggio = NesEnvelope(listOf(2, 0)))
        bank[HAT - 1] = NesInstrument(name = "Closed hat", volume = NesEnvelope(listOf(8, 5, 3, 1, 0)))
        bank[DRUM_KIT - 1] = DpcmStarterKit.instrument(0)
        // The Flute patch with less modulation and feedback: a softer, rounder custom lead.
        bank[FM_LEAD - 1] = NesInstrument(
            name = "Soft lead (custom)",
            chip = NesChip.VRC7,
            fm = Vrc7Patch(preset = Vrc7Presets.CUSTOM, regs = listOf(0x31, 0x61, 0x14, 0x05, 0xa8, 0x64, 0x61, 0x27)),
        )
        bank[FM_BASS - 1] = NesInstrument(name = "Bass guitar", chip = NesChip.VRC7, fm = Vrc7Patch().withPreset(13))
        bank[FM_BRASS - 1] = NesInstrument(
            name = "Brass pad",
            volume = NesEnvelope(listOf(6, 8, 10, 11, 12)),
            chip = NesChip.VRC7,
            fm = Vrc7Patch().withPreset(12),
        )
        bank[FM_PIANO - 1] = NesInstrument(name = "Piano chords", chip = NesChip.VRC7, fm = Vrc7Patch().withPreset(3))
        bank[FM_BELL - 1] = NesInstrument(
            name = "Bell sparkle",
            volume = NesEnvelope(listOf(13, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
            chip = NesChip.VRC7,
            fm = Vrc7Patch().withPreset(Vrc7Presets.BELL),
        )
        return base.copy(
            title = "Lantern Point",
            artist = "Thor Tracker",
            comment = "NES + VRC7 demo: custom-patch FM lead, built-in bass, brass, piano and bell patches, 2A03 echo, triangle and drums. " +
                "Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(intro(), theme(), bridge()),
            orders = listOf(0, 1, 2, 1),
            sound = base.nes.copy(instruments = bank, samples = DpcmStarterKit.samples()),
        )
    }

    private fun intro() = PatternBuilder("Intro", CHANNELS).apply {
        for (bar in 0 until 4) {
            val r = bar * 16
            // FM 6: the chord broken in eighths on the Bell patch.
            for (step in 0 until 8) {
                val offset = intArrayOf(0, 7, 12, 7, 3, 7, 12, 15)[step].let { if (THEME_ARPS[bar] == 0x47 && it % 12 == 3) it + 1 else it }
                note(BELL, r + step * 2, THEME_ROOTS[bar] + offset, FM_BELL)
            }
            if (bar >= 2) bass(bar, THEME_BASS, FM_BASS_CH, FM_BASS)
            for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, HAT_NOTE, HAT)
        }
        fx(BELL, 0, NesRules.FX_VOLUME_SLIDE, 0x4F) // the bells swell in
        fx(BELL, 16, NesRules.FX_VOLUME, 0x0F)
        // Brass pads enter for the last two bars, quietly.
        for (bar in 2 until 4) {
            note(PAD_LOW, bar * 16, PAD_LOWS[bar], FM_BRASS)
            note(PAD_HIGH, bar * 16, PAD_HIGHS[bar], FM_BRASS)
            fx(PAD_LOW, bar * 16, NesRules.FX_VOLUME, 0x09)
            fx(PAD_HIGH, bar * 16, NesRules.FX_VOLUME, 0x09)
        }
        cut(PAD_LOW, 62) // key off: the pads fade with the Brass patch's release
        cut(PAD_HIGH, 62)
        note(NOISE, 56, SNARE_NOTE, SNARE)
        note(NOISE, 60, SNARE_NOTE, SNARE)
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
        // The lead's pickup: B-4 gliding up a fourth into the theme, then a key off.
        note(LEAD, 56, "B-4", FM_LEAD); fx(LEAD, 56, NesRules.FX_SLIDE_UP, 0x05)
        cut(LEAD, 63)
    }.build()

    private fun theme() = PatternBuilder("Theme", CHANNELS).apply {
        val melody = listOf(
            0 to "E-5", 4 to "A-5", 6 to "B-5", 8 to "C-6", 12 to "B-5", 14 to "A-5",
            16 to "A-5", 20 to "C-6", 24 to "F-5", 28 to "A-5",
            32 to "B-5", 36 to "D-6", 40 to "B-5", 44 to "G-5",
            48 to "G#5", 52 to "B-5", 56 to "E-5",
        )
        for ((row, name) in melody) note(LEAD, row, name, FM_LEAD)
        fx(LEAD, 8, NesRules.FX_VIBRATO, 0x63)
        fx(LEAD, 12, NesRules.FX_VIBRATO, 0x00)
        fx(LEAD, 24, NesRules.FX_SLIDE_UP, 0x04) // F-5 rising to A-5
        fx(LEAD, 56, NesRules.FX_VIBRATO, 0x74)
        cut(LEAD, 62) // key off: the lead's release tail
        fx(LEAD, 63, NesRules.FX_VIBRATO, 0x00)
        // 2A03 Pulse 1: a quiet echo of the lead, three rows late and an octave down.
        for ((row, name) in melody) if (row + 3 < 64) {
            note(PULSE1, row + 3, n(name) - 12, ECHO)
            fx(PULSE1, row + 3, NesRules.FX_VOLUME, 0x07)
        }
        for (bar in 0 until 4) {
            val r = bar * 16
            // FM 5: piano arpeggio chords on the beats.
            for (beat in listOf(0, 4, 8, 12)) {
                note(PIANO, r + beat, THEME_ROOTS[bar], FM_PIANO)
                fx(PIANO, r + beat, NesRules.FX_ARPEGGIO, THEME_ARPS[bar])
            }
            // FM 3 / FM 4: a brass pad a bar.
            note(PAD_LOW, r, PAD_LOWS[bar], FM_BRASS)
            note(PAD_HIGH, r, PAD_HIGHS[bar], FM_BRASS)
            fx(PAD_LOW, r, NesRules.FX_VOLUME, 0x0A)
            fx(PAD_HIGH, r, NesRules.FX_VOLUME, 0x0A)
            bass(bar, THEME_BASS, FM_BASS_CH, FM_BASS)
            drums(bar)
        }
        fx(PIANO, 4, NesRules.FX_VOLUME, 0x0C) // the piano steps back behind the lead
        // A late ghost snare, delayed into the middle of its row.
        note(NOISE, 47, SNARE_NOTE, SNARE); fx(NOISE, 47, NesRules.FX_NOTE_DELAY, 0x03)
    }.build()

    private fun bridge() = PatternBuilder("Bridge", CHANNELS).apply {
        // FM 1: a slower answer to the theme, each phrase released by a key off.
        val phrase = listOf(0 to "A-5", 6 to "G-5", 8 to "F-5", 16 to "B-5", 22 to "A-5", 24 to "G-5", 32 to "C-6", 38 to "B-5",
            40 to "A-5", 48 to "G#5", 56 to "B-5")
        for ((row, name) in phrase) note(LEAD, row, name, FM_LEAD)
        for (row in listOf(14, 30, 46)) cut(LEAD, row)
        fx(LEAD, 56, NesRules.FX_SLIDE_DOWN, 0x07) // B-5 sinking to E-5
        cut(LEAD, 63)
        for (bar in 0 until 4) {
            val r = bar * 16
            // FM 6: bell answers every half bar, an octave over the chord.
            note(BELL, r + 4, BRIDGE_ROOTS[bar] + 12, FM_BELL)
            note(BELL, r + 12, BRIDGE_ROOTS[bar] + 19, FM_BELL)
            // FM 5: the chords, sustained.
            note(PIANO, r, BRIDGE_ROOTS[bar], FM_PIANO)
            fx(PIANO, r, NesRules.FX_ARPEGGIO, BRIDGE_ARPS[bar])
            note(PIANO, r + 8, BRIDGE_ROOTS[bar], FM_PIANO)
            fx(PIANO, r + 8, NesRules.FX_ARPEGGIO, BRIDGE_ARPS[bar])
            // FM bass plus the 2A03 triangle an octave under it.
            bass(bar, BRIDGE_BASS, FM_BASS_CH, FM_BASS)
            note(TRIANGLE, r, BRIDGE_BASS[bar], BASS)
            note(TRIANGLE, r + 8, BRIDGE_BASS[bar], BASS)
            drums(bar)
        }
        fx(PIANO, 0, NesRules.FX_VOLUME, 0x0B)
        cut(TRIANGLE, 63)
        cut(PAD_LOW, 0) // the pads rest through the bridge
        cut(PAD_HIGH, 0)
    }.build()

    /** Root, root, octave, root, fifth, octave; the short notes are cut a few frames in. */
    private fun PatternBuilder.bass(bar: Int, roots: IntArray, channel: Int, instrument: Int) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(channel, r + row, roots[bar] + offset, instrument)
            if (row == 3 || row == 6 || row == 11) fx(channel, r + row, NesRules.FX_NOTE_CUT, 0x04)
        }
    }

    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        val hits = listOf(0 to KICK, 2 to HAT, 4 to SNARE, 6 to HAT, 8 to KICK, 10 to HAT, 12 to SNARE, 14 to HAT)
        for ((row, instrument) in hits) {
            note(NOISE, r + row, when (instrument) { KICK -> KICK_NOTE; SNARE -> SNARE_NOTE; else -> HAT_NOTE }, instrument)
        }
        for ((row, note) in listOf(0 to KIT_KICK, 4 to KIT_SNARE, 8 to KIT_KICK, 12 to KIT_SNARE)) note(DPCM, r + row, note, DRUM_KIT)
    }

    /** Noise rates (note mod 16) and starter kit keys, as in the 2A03 demo. */
    private val KICK_NOTE = n("A-3")
    private val SNARE_NOTE = n("F-4")
    private val HAT_NOTE = n("A#4")
    private val KIT_KICK = n("C-3")
    private val KIT_SNARE = n("D-3")
    private val TOM_LOW = n("G-3")
    private val TOM_HIGH = n("A-3")
}
