package org.thortracker.system.nes

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesDemoSong.PatternBuilder
import org.thortracker.system.nes.NesDemoSong.n

/**
 * The bundled NES + MMC5 demo: "Tandem Skies", G major, speed 6. Take-off, theme, crosswind and turnaround, arranged
 * 0-1-2-1-3 (about 32 seconds once through) and looping to order 1. The MMC5's two pulses fly as a pair: MMC5 Pulse 1
 * sings the lead (its duty envelope opens at 50% and settles at 25%, vibrato on the long notes, a slide into the
 * cadence) and MMC5 Pulse 2 the harmony in thirds and sixths (no duty envelope: `Vxx` sets 12.5%, 25% and 50% per
 * section); in the crosswind they trade phrases. The 2A03 keeps a quiet echo of the lead, 75% arpeggio chords, the
 * triangle bass, noise drums and the DPCM starter kit. The lead stays where its periods keep the same high byte under
 * vibrato and slides, so the MMC5's phase reset (no sweep unit to hide it) is not heard. Its MMC5 PCM lane is silent, so
 * it stays a seven-channel song that the `.nsf` and FamiStudio text exports carry whole; [pcmDemo] plays the PCM lane.
 */
object NesMmc5DemoSong {
    private const val ECHO = 1
    private const val BASS = 2
    private const val KICK = 3
    private const val SNARE = 4
    private const val HAT = 5
    private const val DRUM_KIT = 6
    private const val LEAD = 7
    private const val HARMONY = 8
    private const val CHORDS = 9
    private const val PLUCK = 10

    private const val PULSE1 = 0
    private const val PULSE2 = 1
    private const val TRIANGLE = NesRules.TRIANGLE
    private const val NOISE = NesRules.NOISE
    private const val DPCM = NesRules.DPCM
    private const val M_PULSE1 = NesRules.MMC5_PULSE1
    private const val M_PULSE2 = NesRules.MMC5_PULSE2
    private const val M_PCM = NesRules.MMC5_PCM
    private val CHANNELS = NesExpansion.MMC5.channels

    /** Theme G D Em C, crosswind Am C D B, turnaround C D G D: chord roots for the 2A03 arpeggios and their 0xy. */
    private val THEME_CHORDS = intArrayOf(n("G-3"), n("D-4"), n("E-4"), n("C-4"))
    private val THEME_ARPS = intArrayOf(0x47, 0x47, 0x37, 0x47)
    private val THEME_BASS = intArrayOf(n("G-2"), n("D-3"), n("E-3"), n("C-3"))
    private val CROSS_CHORDS = intArrayOf(n("A-3"), n("C-4"), n("D-4"), n("B-3"))
    private val CROSS_ARPS = intArrayOf(0x37, 0x47, 0x47, 0x47)
    private val CROSS_BASS = intArrayOf(n("A-2"), n("C-3"), n("D-3"), n("B-2"))
    private val TURN_CHORDS = intArrayOf(n("C-4"), n("D-4"), n("G-3"), n("D-4"))
    private val TURN_ARPS = intArrayOf(0x47, 0x47, 0x47, 0x47)
    private val TURN_BASS = intArrayOf(n("C-3"), n("D-3"), n("G-2"), n("D-3"))

    fun build(): Song {
        val base = NesMmc5System.blank()
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
        bank[LEAD - 1] = NesInstrument(
            name = "MMC5 lead 50>25%",
            volume = NesEnvelope(listOf(13, 12, 11, 11, 10, 10, 8, 6, 4, 2, 0), loop = 4, release = 6),
            duty = NesEnvelope(listOf(2, 2, 2, 2, 1)),
        )
        bank[HARMONY - 1] = NesInstrument(
            name = "MMC5 harmony (Vxx)",
            volume = NesEnvelope(listOf(10, 9, 9, 8, 8, 7, 7, 6), loop = 7),
        )
        bank[CHORDS - 1] = NesInstrument(
            name = "Chords 75%",
            volume = NesEnvelope(listOf(8, 7, 6, 5, 5, 4, 4, 3, 3, 2)),
            duty = NesEnvelope(listOf(3)),
        )
        bank[PLUCK - 1] = NesInstrument(
            name = "MMC5 pluck",
            volume = NesEnvelope(listOf(12, 10, 8, 6, 5, 4, 3, 2, 1, 0)),
            duty = NesEnvelope(listOf(0, 1)),
        )
        return base.copy(
            title = "Tandem Skies",
            artist = "Thor Tracker",
            comment = "NES + MMC5 demo: the MMC5 pulses as lead and harmony (duty envelope and Vxx, vibrato, slides, traded " +
                "phrases), 2A03 echo, chords, bass and drums. Loops to order 1.",
            ticksPerRow = 6,
            loopOrder = 1,
            patterns = listOf(takeoff(), theme(), crosswind(), turnaround()),
            orders = listOf(0, 1, 2, 1, 3),
            sound = base.nes.copy(instruments = bank, samples = DpcmStarterKit.samples()),
        )
    }

    /**
     * The shortest valid NES + MMC5 song (the WAV export test's `mmc5-shortest.nesimg`): one order of a one-row
     * pattern at speed 1, looping to order 0, holding A-4 on MMC5 Pulse 1 with sound 01 (flat full volume), so every
     * pass sounds.
     */
    fun shortest(): Song {
        val base = NesMmc5System.blank()
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(CHANNELS) { ch -> List(Songs.ROWS) { row -> if (ch == M_PULSE1 && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "MMC5 shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
        )
    }

    /**
     * "Sample Courier", the MMC5 PCM lane fixture (the parent's smoke, WAV and native checks; not the bundled demo, whose
     * seven channels export whole): speed 6, two 64-row patterns arranged 0-1-0-1, looping to order 0. The PCM lane plays
     * the synthesized kit ([NesPcmKit]): in "Kit" kicks at full volume (C0F) and hats at C07, one hat delayed (G03),
     * one kick cut short (S02); in "Bass" the looping bass sample chromatically (pitch = key - A-2) with OFF, and the
     * lane's own F04 / F06 changing the song speed. MMC5 Pulse 1 and the triangle play a line and a bass over it.
     */
    fun pcmDemo(): Song {
        val base = NesMmc5System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[PCM_DRUMS - 1] = NesPcmKit.drums(0)
        bank[PCM_BASS - 1] = NesPcmKit.bassSound(0)
        bank[PCM_LEAD - 1] = NesInstrument(
            name = "MMC5 lead",
            volume = NesEnvelope(listOf(12, 11, 10, 9, 9, 8, 8, 7), loop = 7),
            duty = NesEnvelope(listOf(2)),
        )
        bank[PCM_TRI - 1] = NesInstrument(name = "Triangle bass")
        val kit = PatternBuilder("Kit", CHANNELS).apply {
            for (bar in 0 until 4) {
                val r = bar * 16
                for (beat in listOf(0, 8)) { note(M_PCM, r + beat, NesPcmKit.KICK_KEY, PCM_DRUMS); fx(M_PCM, r + beat, NesRules.FX_VOLUME, 0x0F) }
                for (off in listOf(4, 12)) { note(M_PCM, r + off, NesPcmKit.HAT_KEY, PCM_DRUMS); fx(M_PCM, r + off, NesRules.FX_VOLUME, 0x07) }
                note(M_PULSE1, r, listOf("D-5", "B-4", "C-5", "A-4")[bar], PCM_LEAD)
                note(M_PULSE1, r + 8, listOf("G-5", "E-5", "E-5", "F#5")[bar], PCM_LEAD)
                note(TRIANGLE, r, listOf("G-3", "E-3", "C-3", "D-3")[bar], PCM_TRI)
            }
            note(M_PCM, 30, NesPcmKit.HAT_KEY, PCM_DRUMS); fx(M_PCM, 30, NesRules.FX_NOTE_DELAY, 0x03) // a hat delayed 3 frames
            fx(M_PCM, 56, NesRules.FX_NOTE_CUT, 0x02) // that kick is cut after 2 frames
        }.build()
        val bassLine = listOf(0 to "G-2", 6 to "G-2", 8 to "D-3", 16 to "E-2", 22 to "E-2", 24 to "B-2", 32 to "C-3",
            38 to "C-3", 40 to "G-2", 48 to "D-2", 52 to "F#2", 56 to "A-2")
        val bass = PatternBuilder("Bass", CHANNELS).apply {
            for ((row, name) in bassLine) note(M_PCM, row, name, PCM_BASS)
            cut(M_PCM, 62) // OFF stops the loop
            fx(M_PCM, 32, NesRules.FX_SPEED, 0x04) // the PCM lane's own Fxx: the song speeds up ...
            fx(M_PCM, 48, NesRules.FX_SPEED, 0x06) // ... and back
            for (bar in 0 until 4) note(M_PULSE1, bar * 16, listOf("B-4", "G-4", "E-4", "F#4")[bar], PCM_LEAD)
        }.build()
        return base.copy(
            title = "Sample Courier",
            artist = "Thor Tracker",
            comment = "NES + MMC5 PCM lane fixture: synthesized kick, hat and looping bass on Thor Tracker's software PCM " +
                "player (Cxx, Gxx, Sxx, OFF, the lane's own Fxx). Plays and exports as WAV; .nsf and FamiStudio text refuse it.",
            ticksPerRow = 6,
            loopOrder = 0,
            patterns = listOf(kit, bass),
            orders = listOf(0, 1, 0, 1),
            sound = base.nes.copy(instruments = bank, pcmSamples = NesPcmKit.samples()),
        )
    }

    /**
     * The shortest PCM song (`mmc5-pcm-shortest.nesimg`): one order of a one-row pattern at speed 1, looping to order 0,
     * holding the looping PCM bass on A-2, so every pass sounds.
     */
    fun pcmShortest(): Song {
        val base = NesMmc5System.blank()
        val bank = base.nes.instruments.toMutableList().also { it[0] = NesPcmKit.bassSound(0) }
        val held = Cell(note = NesPcmKit.BASS_ROOT, instrument = 1)
        val tracks = List(CHANNELS) { ch -> List(Songs.ROWS) { row -> if (ch == M_PCM && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "MMC5 PCM shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = base.nes.copy(instruments = bank, pcmSamples = NesPcmKit.samples()),
        )
    }

    private const val PCM_DRUMS = 1
    private const val PCM_BASS = 2
    private const val PCM_LEAD = 3
    private const val PCM_TRI = 4

    /** The pulses climb in tandem, one on the beat and one between, gliding into the theme. */
    private fun takeoff() = PatternBuilder("Take-off", CHANNELS).apply {
        val chords = listOf(
            listOf("G-4", "B-4", "D-5", "G-5", "B-5"),
            listOf("F#4", "A-4", "D-5", "F#5", "A-5"),
            listOf("E-4", "G-4", "B-4", "E-5", "G-5"),
            listOf("C-4", "E-4", "G-4", "C-5", "E-5"),
        )
        for (bar in 0 until 4) {
            val r = bar * 16
            val tones = chords[bar]
            for (step in 0 until 4) {
                if (bar == 3 && step == 2) break // room for the pickup
                note(M_PULSE1, r + step * 4, tones[step], PLUCK)
                note(M_PULSE2, r + step * 4 + 2, tones[step + 1], PLUCK)
            }
            if (bar >= 2) bass(bar, THEME_BASS)
            for (row in listOf(2, 6, 10, 14)) note(NOISE, r + row, HAT_NOTE, HAT)
        }
        fx(M_PULSE1, 0, NesRules.FX_VOLUME_SLIDE, 0x3F) // the pair swells in
        fx(M_PULSE2, 2, NesRules.FX_VOLUME_SLIDE, 0x3F)
        fx(M_PULSE1, 16, NesRules.FX_VOLUME, 0x0F)
        fx(M_PULSE2, 18, NesRules.FX_VOLUME, 0x0F)
        // MMC5 Pulse 1's pickup: B-4 gliding up a third to the theme's D-5.
        note(M_PULSE1, 56, "B-4", LEAD); fx(M_PULSE1, 56, NesRules.FX_SLIDE_UP, 0x03)
        cut(M_PULSE1, 63)
        cut(M_PULSE2, 62)
        note(NOISE, 56, SNARE_NOTE, SNARE)
        note(NOISE, 60, SNARE_NOTE, SNARE)
        note(DPCM, 60, TOM_HIGH, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
    }.build()

    /** Lead and harmony side by side, the 2A03 echoing the lead three rows late under 75% chords. */
    private fun theme() = PatternBuilder("Theme", CHANNELS).apply {
        val lead = listOf(
            0 to "D-5", 4 to "G-5", 6 to "A-5", 8 to "B-5", 12 to "A-5", 14 to "G-5",
            16 to "F#5", 20 to "A-5", 24 to "D-5", 28 to "F#5",
            32 to "G-5", 36 to "B-5", 40 to "E-5", 44 to "G-5",
            48 to "E-5", 56 to "D-5",
        )
        val harmony = listOf(
            0 to "B-4", 4 to "D-5", 6 to "F#5", 8 to "G-5", 12 to "F#5", 14 to "D-5",
            16 to "D-5", 20 to "F#5", 24 to "A-4", 28 to "D-5",
            32 to "E-5", 36 to "G-5", 40 to "B-4", 44 to "E-5",
            48 to "C-5", 56 to "B-4",
        )
        for ((row, name) in lead) note(M_PULSE1, row, name, LEAD)
        for ((row, name) in harmony) note(M_PULSE2, row, name, HARMONY)
        fx(M_PULSE1, 8, NesRules.FX_VIBRATO, 0x63)
        fx(M_PULSE1, 12, NesRules.FX_VIBRATO, 0x00)
        fx(M_PULSE1, 48, NesRules.FX_SLIDE_UP, 0x03) // E-5 rising to G-5 as D-5 lands
        fx(M_PULSE1, 56, NesRules.FX_VIBRATO, 0x74)
        cut(M_PULSE1, 62) // releases the lead into its tail
        fx(M_PULSE1, 63, NesRules.FX_VIBRATO, 0x00)
        // MMC5 Pulse 2's duty is all Vxx: thin 12.5% for the first half, 25% for the second.
        fx(M_PULSE2, 0, NesRules.FX_DUTY, 0x00)
        fx(M_PULSE2, 32, NesRules.FX_DUTY, 0x01)
        cut(M_PULSE2, 62)
        // 2A03 Pulse 1: a quiet echo of the lead, three rows late.
        for ((row, name) in lead) if (row + 3 < 64) { note(PULSE1, row + 3, name, ECHO); fx(PULSE1, row + 3, NesRules.FX_VOLUME, 0x07) }
        for (bar in 0 until 4) {
            chords(bar, THEME_CHORDS, THEME_ARPS)
            bass(bar, THEME_BASS)
            drums(bar)
        }
        // A late ghost snare, delayed into the middle of its row.
        note(NOISE, 47, SNARE_NOTE, SNARE); fx(NOISE, 47, NesRules.FX_NOTE_DELAY, 0x03)
    }.build()

    /** The pulses trade phrases: one plays, the other answers while the first holds a long note under it. */
    private fun crosswind() = PatternBuilder("Crosswind", CHANNELS).apply {
        // MMC5 Pulse 1 calls (bars 1 and 3), MMC5 Pulse 2 answers at 50% (bars 2 and 4).
        val call = listOf(0 to "A-5", 2 to "G-5", 4 to "E-5", 8 to "C-5", 32 to "A-5", 34 to "F#5", 36 to "D-5", 40 to "D-5")
        val answer = listOf(16 to "G-5", 18 to "E-5", 20 to "C-5", 24 to "E-5", 48 to "F#5", 50 to "D#5", 52 to "B-4", 56 to "D#5")
        for ((row, name) in call) note(M_PULSE1, row, name, LEAD)
        for ((row, name) in answer) note(M_PULSE2, row, name, HARMONY)
        // Each holds a long note under the other's phrase.
        note(M_PULSE2, 0, "E-5", HARMONY); fx(M_PULSE2, 0, NesRules.FX_DUTY, 0x02)
        fx(M_PULSE2, 1, NesRules.FX_VOLUME, 0x09)
        fx(M_PULSE2, 16, NesRules.FX_VOLUME, 0x0F)
        note(M_PULSE2, 32, "A-4", HARMONY); fx(M_PULSE2, 32, NesRules.FX_VOLUME, 0x09)
        fx(M_PULSE2, 48, NesRules.FX_VOLUME, 0x0F)
        note(M_PULSE1, 16, "G-4", LEAD); fx(M_PULSE1, 16, NesRules.FX_VOLUME, 0x08)
        fx(M_PULSE1, 32, NesRules.FX_VOLUME, 0x0F)
        note(M_PULSE1, 48, "B-4", LEAD); fx(M_PULSE1, 48, NesRules.FX_VOLUME, 0x08)
        fx(M_PULSE1, 8, NesRules.FX_VIBRATO, 0x53) // C-5 wavering
        fx(M_PULSE1, 14, NesRules.FX_VIBRATO, 0x00)
        fx(M_PULSE1, 40, NesRules.FX_SLIDE_UP, 0x04) // D-5 climbing to F#5 by the answer
        cut(M_PULSE1, 63)
        cut(M_PULSE2, 63)
        // The echo follows whichever pulse leads.
        for ((row, name) in (call + answer).sortedBy { it.first }) if (row + 3 < 64) {
            note(PULSE1, row + 3, name, ECHO); fx(PULSE1, row + 3, NesRules.FX_VOLUME, 0x07)
        }
        for (bar in 0 until 4) {
            chords(bar, CROSS_CHORDS, CROSS_ARPS)
            bass(bar, CROSS_BASS)
            drums(bar)
        }
    }.build()

    /** Parallel thirds climbing back to the theme, the lead's last note with vibrato. */
    private fun turnaround() = PatternBuilder("Turnaround", CHANNELS).apply {
        val lead = listOf(
            0 to "E-5", 2 to "G-5", 4 to "C-6", 8 to "B-5", 12 to "G-5",
            16 to "F#5", 18 to "A-5", 20 to "D-6", 24 to "C-6", 28 to "A-5",
            32 to "G-5", 36 to "B-5", 40 to "D-6",
            48 to "C-6", 52 to "B-5", 56 to "A-5", 60 to "F#5",
        )
        val harmony = listOf(
            0 to "C-5", 2 to "E-5", 4 to "G-5", 8 to "G-5", 12 to "E-5",
            16 to "D-5", 18 to "F#5", 20 to "A-5", 24 to "A-5", 28 to "F#5",
            32 to "D-5", 36 to "G-5", 40 to "B-5",
            48 to "F#5", 52 to "G-5", 56 to "F#5", 60 to "D-5",
        )
        for ((row, name) in lead) note(M_PULSE1, row, name, LEAD)
        for ((row, name) in harmony) note(M_PULSE2, row, name, HARMONY)
        fx(M_PULSE2, 0, NesRules.FX_DUTY, 0x01)
        fx(M_PULSE1, 40, NesRules.FX_VIBRATO, 0x64)
        fx(M_PULSE1, 48, NesRules.FX_VIBRATO, 0x00)
        for ((row, name) in lead) if (row + 3 < 64) { note(PULSE1, row + 3, name, ECHO); fx(PULSE1, row + 3, NesRules.FX_VOLUME, 0x07) }
        for (bar in 0 until 4) {
            chords(bar, TURN_CHORDS, TURN_ARPS)
            bass(bar, TURN_BASS)
            drums(bar)
        }
        // A tom fill hands back to the theme.
        note(DPCM, 58, TOM_HIGH, DRUM_KIT)
        note(DPCM, 60, TOM_LOW, DRUM_KIT)
        note(DPCM, 62, TOM_LOW, DRUM_KIT)
    }.build()

    /** 2A03 Pulse 2: 75% arpeggio chords on the beats. */
    private fun PatternBuilder.chords(bar: Int, roots: IntArray, arps: IntArray) {
        val r = bar * 16
        for (beat in listOf(0, 4, 8, 12)) {
            note(PULSE2, r + beat, roots[bar], CHORDS)
            fx(PULSE2, r + beat, NesRules.FX_ARPEGGIO, arps[bar])
        }
    }

    /** Root, root, octave, root, fifth, octave; the short notes are cut a few frames in. */
    private fun PatternBuilder.bass(bar: Int, roots: IntArray) {
        val r = bar * 16
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 7, 14 to 12)) {
            note(TRIANGLE, r + row, roots[bar] + offset, BASS)
            if (row == 3 || row == 6 || row == 11) fx(TRIANGLE, r + row, NesRules.FX_NOTE_CUT, 0x04)
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
