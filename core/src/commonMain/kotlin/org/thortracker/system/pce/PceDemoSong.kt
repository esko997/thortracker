package org.thortracker.system.pce

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.pce.PceRules.FX_ARPEGGIO
import org.thortracker.system.pce.PceRules.FX_LFO_CTRL
import org.thortracker.system.pce.PceRules.FX_LFO_FREQ
import org.thortracker.system.pce.PceRules.FX_MASTER
import org.thortracker.system.pce.PceRules.FX_PAN
import org.thortracker.system.pce.PceRules.FX_PORTAMENTO
import org.thortracker.system.pce.PceRules.FX_TIMER
import org.thortracker.system.pce.PceRules.FX_VIBRATO
import org.thortracker.system.pce.PceRules.FX_VOLUME
import org.thortracker.system.pce.PceRules.FX_WAVE

/**
 * The bundled PC Engine demo: "Turbo Lanterns", an original HuCard-style stage theme in D minor, Dm-B♭-C-A, 6 ticks
 * per row (150 BPM). Intro, theme A, theme B, theme A again and the "Lantern Drift" passage, looping to theme A. Every
 * channel plays:
 *
 * - PSG 1: the lead, a tone whose wave envelope cycles the pulse widths (25 %, square, 25 %, 12.5 %) for a shimmer,
 *   with vibrato and a portamento run in theme B; in the drift it holds a pad that the chip's LFO bends.
 * - PSG 2: glassy chord arpeggios (`0xy`) on the Bell wave, panned (`8xy`), switched to the Reed and Organ waves by
 *   `Wxx` in theme B; in the drift it is the LFO's source: a sine held at volume 0 (`C00`) whose note and `Lxx` set
 *   the LFO's rate, `Mxx` its depth (shallow, then deep, then off).
 * - PSG 3: the bass.
 * - PSG 4: direct-D/A drums from the starter kit (kick, snare, a tom fill); in the drift `T01` halves the DDA rate, so
 *   the toms play an octave down and slower, and `T00` restores it.
 * - PSG 5 and PSG 6: noise hi-hats, open hats, a noise snare layered on the D/A snare, and crashes, panned apart.
 *
 * The intro fades the master balance in (`X88` .. `XFF`); the drift leans it left and theme A restores it.
 * The demo's own sounds are slots 01-0C; the presets ([PcePresets.ALL]) follow them.
 */
object PceDemoSong {
    private const val SPEED = 6

    private const val LEAD_CH = 0
    private const val ARP_CH = 1
    private const val BASS_CH = 2
    private const val DDA_CH = 3
    private const val HAT_CH = 4
    private const val NOISE_CH = 5

    private const val LEAD = 1
    private const val ARP = 2
    private const val BASS = 3
    private const val LFO_SOURCE = 4
    private const val DRIFT = 5
    private const val KICK = 6
    private const val SNARE = 7
    private const val TOM = 8
    private const val HAT = 9
    private const val OPEN_HAT = 10
    private const val NOISE_SNARE = 11
    private const val CRASH = 12

    // The built-in waves' indices ([PcePresets.WAVES]).
    private const val W_SINE = 0
    private const val W_SQUARE = 3
    private const val W_PULSE_25 = 4
    private const val W_PULSE_12 = 5
    private const val W_ORGAN = 6
    private const val W_SOFT_SAW = 8
    private const val W_BASS = 9
    private const val W_REED = 10
    private const val W_BELL = 11

    private val BASS_ROOTS = listOf(n("D-2"), n("A#1"), n("C-2"), n("A-1"))

    /** Each bar's arpeggio: root note and `0xy` (minor 037, major 047). */
    private val CHORDS = listOf(n("D-4") to 0x37, n("A#3") to 0x47, n("C-4") to 0x47, n("A-3") to 0x47)

    /** Noise notes: the note picks the rate (note mod 32); C-5 = rate 28, G#4 = 24, D-5 = 30. */
    private val HAT_NOTE = n("C-5")
    private val SNARE_NOISE_NOTE = n("G#4")
    private val CRASH_NOTE = n("D-5")

    /** The D/A drums' root: they play at the DDA rate on it. */
    private val DDA_NOTE = n("C-4")

    fun song(): Song {
        val blank = PceSystem.blank()
        val waves = PcePresets.WAVES.map { it.second }
        val kit = PceStarterKit.samples()
        val own = listOf(lead(), arp(), bass(), lfoSource(), driftPad(),
            PceStarterKit.instrument(PceStarterKit.KICK, "D/A kick"),
            PceStarterKit.instrument(PceStarterKit.SNARE, "D/A snare"),
            PceStarterKit.instrument(PceStarterKit.TOM, "D/A tom"),
            preset("Hi-hat"), preset("Open hat"), preset("Snare").copy(name = "Noise snare"), preset("Crash"))
        val presets = PcePresets.ALL.map { p -> p.instrument.copy(wave = p.wave?.let { waves.indexOf(it) }?.coerceAtLeast(0) ?: 0) }
        val instruments = (own + presets).take(PceSystem.SLOTS).let { it + List(PceSystem.SLOTS - it.size) { null } }
        return blank.copy(
            title = "Turbo Lanterns",
            artist = "Thor Tracker",
            comment = "PC Engine demo: wave-envelope lead, Wxx waves, noise hats on PSG 5-6, D/A drums from the starter kit, " +
                "an LFO passage (PSG 2 bends PSG 1) and master/channel balance. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), themeB(), lanternDrift()),
            orders = listOf(0, 1, 2, 1, 3),
            sound = PceSound(instruments = instruments, waves = waves, samples = kit),
        )
    }

    /**
     * The shortest valid PC Engine song (the WAV export test's `pce-shortest.pcimg`): one order of a one-row pattern
     * at speed 1, looping to order 0, holding A-4 on PSG 1 with slot 01, a tone at constant full volume on wave 0 (a
     * sine), so every pass sounds.
     */
    fun shortest(): Song {
        val base = PceSystem.blank()
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(PceRules.CHANNEL_NAMES.size) { ch -> List(Songs.ROWS) { row -> if (ch == LEAD_CH && row == 0) held else Cell.BLANK } }
        return base.copy(
            title = "PC Engine shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = PceSound(
                instruments = List(PceSystem.SLOTS) { if (it == 0) PceInstrument("Sine", PceKind.TONE, wave = 0) else null },
                waves = listOf(PcePresets.WAVES[W_SINE].second),
            ),
        )
    }

    // ---- sounds -----------------------------------------------------------------------------------------------

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = PceEnvelope(values.toList(), loop, release)

    private fun preset(name: String): PceInstrument = PcePresets.named(name)?.instrument ?: throw IllegalArgumentException("No PC Engine preset \"$name\"")

    /** Pulse widths cycling every 12 ticks (25 %, square, 25 %, 12.5 %): a shimmering lead; fades after OFF. */
    private fun lead() = PceInstrument(
        "Lantern lead", PceKind.TONE, wave = W_PULSE_25,
        volume = env(31, 29, 27, 26, 25, 24, 20, 15, 10, 5, 0, release = 6),
        waves = env(W_PULSE_25, W_PULSE_25, W_PULSE_25, W_SQUARE, W_SQUARE, W_SQUARE, W_PULSE_25, W_PULSE_25, W_PULSE_25,
            W_PULSE_12, W_PULSE_12, W_PULSE_12, loop = 0),
    )

    /** A struck glass tone on the Bell wave, dying away; no wave envelope, so `Wxx` changes its wave. */
    private fun arp() = PceInstrument(
        "Glass arp", PceKind.TONE, wave = W_BELL,
        volume = env(31, 28, 26, 24, 22, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )

    private fun bass() = PceInstrument(
        "Round bass", PceKind.TONE, wave = W_BASS,
        volume = env(31, 30, 28, 26, 24, 22, 21, 20, 12, 4, 0, release = 8),
    )

    /** The LFO's shape (a sine) at constant volume; the song plays it at C00 so only the vibrato is heard. */
    private fun lfoSource() = PceInstrument("LFO sine", PceKind.TONE, wave = W_SINE, volume = env(31))

    /** A slow-swelling soft saw held under the LFO. */
    private fun driftPad() = PceInstrument(
        "Drift pad", PceKind.TONE, wave = W_SOFT_SAW,
        volume = env(8, 12, 16, 19, 22, 25, 27, 29, 30, 31, 24, 18, 12, 6, 0, release = 10),
    )

    // ---- patterns ---------------------------------------------------------------------------------------------

    private fun intro() = PatternBuilder("Intro").apply {
        // The master balance fades in, bar by bar.
        for ((bar, x) in listOf(0x88, 0xAA, 0xCC, 0xFF).withIndex()) fx(LEAD_CH, bar * 16, 1, FX_MASTER, x)
        fx(ARP_CH, 0, 1, FX_PAN, 0xF9)
        fx(HAT_CH, 0, 0, FX_PAN, 0xAF)
        for (bar in 0 until 4) {
            val r = bar * 16
            bass(bar)
            arps(bar)
            if (bar >= 1) hats(bar)
            if (bar >= 2) {
                note(DDA_CH, r, DDA_NOTE, KICK)
                note(DDA_CH, r + 8, DDA_NOTE, KICK)
            }
        }
        // A noise snare roll and the lead's pickup into theme A.
        for (row in listOf(56, 58, 60, 62)) note(NOISE_CH, row, SNARE_NOISE_NOTE, NOISE_SNARE)
        note(LEAD_CH, 56, n("D-4"), LEAD)
        note(LEAD_CH, 60, n("E-4"), LEAD)
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        // After the drift: full master balance, PSG 2 audible again, the LFO's depth already off.
        fx(NOISE_CH, 0, 1, FX_MASTER, 0xFF)
        fx(ARP_CH, 0, 2, FX_VOLUME, 0x1F)
        fx(ARP_CH, 0, 3, FX_PAN, 0xF9)
        for (bar in 0 until 4) { bass(bar); arps(bar); drums(bar) }
        note(NOISE_CH, 0, CRASH_NOTE, CRASH)
        lead(listOf(0 to "A-4", 4 to "D-5", 6 to "E-5", 8 to "F-5", 12 to "E-5", 14 to "D-5",
            16 to "F-5", 20 to "D-5", 24 to "A#4", 28 to "D-5",
            32 to "E-5", 36 to "G-5", 38 to "F-5", 40 to "E-5", 44 to "C-5",
            48 to "C#5", 52 to "E-5", 56 to "A-5"))
        cut(LEAD_CH, 60)
    }.build()

    private fun themeB() = PatternBuilder("Theme B").apply {
        for (bar in 0 until 4) { bass(bar); arps(bar); drums(bar, fill = bar == 3) }
        note(NOISE_CH, 0, CRASH_NOTE, CRASH)
        // The arpeggios swap to the right and change waves: Reed, then Organ, then (a note without W) the Bell again.
        fx(ARP_CH, 0, 1, FX_PAN, 0x9F)
        fx(ARP_CH, 0, 2, FX_WAVE, W_REED)
        fx(ARP_CH, 32, 2, FX_WAVE, W_ORGAN)
        lead(listOf(0 to "A-5", 8 to "G-5", 10 to "F-5", 12 to "E-5",
            16 to "F-5", 20 to "A#5", 24 to "A-5", 28 to "F-5",
            32 to "G-5", 36 to "E-5", 40 to "C-5", 44 to "E-5",
            48 to "C#5"))
        fx(LEAD_CH, 0, 0, FX_VIBRATO, 0x34)
        // A portamento run up to A-5 without restarting the note, ended with the OFF.
        note(LEAD_CH, 52, n("E-5"), 0)
        fx(LEAD_CH, 52, 0, FX_PORTAMENTO, 0x18)
        note(LEAD_CH, 56, n("A-5"), 0)
        cut(LEAD_CH, 60)
        fx(LEAD_CH, 60, 0, FX_PORTAMENTO, 0x00)
        fx(LEAD_CH, 60, 1, FX_VIBRATO, 0x00)
    }.build()

    /**
     * "Lantern Drift": PSG 2 holds a sine at volume 0 as the LFO's source (A-2 at L10: about 6.9 Hz; then L20, half as
     * fast), M01 bends PSG 1's held pad gently, M02 deeply, M00 ends it; T01 halves the DDA rate for the tom fill (an
     * octave down) and T00 restores it; the master balance leans left.
     */
    private fun lanternDrift() = PatternBuilder("Lantern Drift").apply {
        fx(NOISE_CH, 0, 1, FX_MASTER, 0xF9)
        note(NOISE_CH, 0, CRASH_NOTE, CRASH)
        // The LFO's source and settings.
        note(ARP_CH, 0, n("A-2"), LFO_SOURCE)
        fx(ARP_CH, 0, 0, FX_VOLUME, 0x00)
        fx(ARP_CH, 0, 1, FX_LFO_FREQ, 0x10)
        fx(ARP_CH, 0, 2, FX_LFO_CTRL, 0x01)
        fx(ARP_CH, 32, 0, FX_LFO_FREQ, 0x20)
        fx(ARP_CH, 48, 0, FX_LFO_CTRL, 0x02)
        fx(ARP_CH, 60, 0, FX_LFO_CTRL, 0x00)
        cut(ARP_CH, 62)
        // PSG 1's pad under the LFO.
        note(LEAD_CH, 0, n("D-4"), DRIFT)
        note(LEAD_CH, 32, n("F-4"), DRIFT)
        note(LEAD_CH, 48, n("A-4"), DRIFT)
        cut(LEAD_CH, 60)
        // The bass in long notes.
        for (bar in 0 until 4) note(BASS_CH, bar * 16, BASS_ROOTS[bar], BASS)
        cut(BASS_CH, 62)
        // Sparse D/A kicks, then the half-rate tom fill.
        for (row in listOf(0, 16, 32)) note(DDA_CH, row, DDA_NOTE, KICK)
        fx(DDA_CH, 48, 0, FX_TIMER, 0x01)
        for ((row, name) in listOf(48 to "C-4", 52 to "A-3", 56 to "F-3")) note(DDA_CH, row, n(name), TOM)
        fx(DDA_CH, 62, 0, FX_TIMER, 0x00)
        for (row in listOf(8, 24, 40)) note(HAT_CH, row, HAT_NOTE, HAT)
        note(HAT_CH, 56, HAT_NOTE, OPEN_HAT)
    }.build()

    /** Root, octave and fifth in eighths. */
    private fun PatternBuilder.bass(bar: Int) {
        val r = bar * 16
        val root = BASS_ROOTS[bar]
        for ((row, offset) in listOf(0 to 0, 4 to 12, 6 to 0, 8 to 0, 12 to 12, 14 to 7)) note(BASS_CH, r + row, root + offset, BASS)
    }

    /** The bar's chord as two struck arpeggios. */
    private fun PatternBuilder.arps(bar: Int) {
        val (root, arp) = CHORDS[bar]
        for (row in listOf(0, 8)) {
            note(ARP_CH, bar * 16 + row, root, ARP)
            fx(ARP_CH, bar * 16 + row, 0, FX_ARPEGGIO, arp)
        }
    }

    /** Closed hats in eighths with an open hat on the last. */
    private fun PatternBuilder.hats(bar: Int) {
        val r = bar * 16
        for (row in listOf(0, 2, 4, 6, 8, 10, 12)) note(HAT_CH, r + row, HAT_NOTE, HAT)
        note(HAT_CH, r + 14, HAT_NOTE, OPEN_HAT)
    }

    /** D/A kick and snare, the noise snare on top, hats; [fill]: a D/A tom fill in the bar's last beat. */
    private fun PatternBuilder.drums(bar: Int, fill: Boolean = false) {
        val r = bar * 16
        note(DDA_CH, r, DDA_NOTE, KICK)
        note(DDA_CH, r + 4, DDA_NOTE, SNARE)
        note(DDA_CH, r + 8, DDA_NOTE, KICK)
        if (bar % 2 == 1 && !fill) note(DDA_CH, r + 10, DDA_NOTE, KICK)
        if (fill) {
            for ((row, name) in listOf(10 to "G-4", 12 to "E-4", 14 to "C-4")) note(DDA_CH, r + row, n(name), TOM)
        } else {
            note(DDA_CH, r + 12, DDA_NOTE, SNARE)
        }
        for (row in listOf(4, 12)) note(NOISE_CH, r + row, SNARE_NOISE_NOTE, NOISE_SNARE)
        hats(bar)
    }

    private fun PatternBuilder.lead(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(LEAD_CH, row, n(name), LEAD)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String) {
        private val tracks = List(PceRules.CHANNEL_NAMES.size) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = Cell.CUT, instrument = 0)
        }

        fun fx(channel: Int, row: Int, column: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].withEffect(column, effect, param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() })
    }
}
