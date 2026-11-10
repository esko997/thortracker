package org.thortracker.system.ws

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.ws.WsRules.FX_ARPEGGIO
import org.thortracker.system.ws.WsRules.FX_HOLD
import org.thortracker.system.ws.WsRules.FX_PAN
import org.thortracker.system.ws.WsRules.FX_PORTAMENTO
import org.thortracker.system.ws.WsRules.FX_SWEEP
import org.thortracker.system.ws.WsRules.FX_SWEEP_TIME
import org.thortracker.system.ws.WsRules.FX_TAP
import org.thortracker.system.ws.WsRules.FX_VIBRATO
import org.thortracker.system.ws.WsRules.FX_VOLUME
import org.thortracker.system.ws.WsRules.FX_WAVE

/**
 * The bundled WonderSwan demos, original tunes written for Thor Tracker, both in 32-row patterns at 7 ticks per row
 * (75.47 ticks per second: about 162 BPM in quarter notes of four rows), heard through headphones:
 *
 * - [mono], "Paper Crane Run" (WonderSwan, E minor, Em-C-G-D): CH1 a pulse lead whose wave `Wxx` changes in theme B
 *   (square, then the vowel wave); CH2 voice drums streamed by the CPU from the starter kit (kick, snare); CH3 the bass
 *   with hardware-sweep lasers at phrase ends and a `Uxx`/`Vxx` sweep override; CH4 noise hats and a metallic tap-mode
 *   noise stepped through tap modes by `Nxx`. Intro, theme A, theme B, theme A, looping to theme A.
 * - [color], "Lantern Tide" (WonderSwan Color, D dorian, Dm-G-Am-C): the four channels as above with Sound DMA drums on
 *   CH2 (a one-shot kick; a looping shaker gated by `H01`/`H00`), and Hyper Voice playing a stereo snare by Sound DMA
 *   (which takes the DMA from CH2), a reversed stereo snare (DMA backwards) and a pitched tom melody fed by the CPU,
 *   panned side to side. Intro "Shore", tide A, tide B, tide A, looping to tide A.
 *
 * Each demo's own sounds come first; the tone and noise presets ([WsPresets.presets]) follow them.
 */
object WsDemoSong {
    private const val SPEED = 7
    private const val ROWS = 32

    private const val LEAD_CH = 0
    private const val DRUM_CH = 1
    private const val BASS_CH = 2
    private const val NOISE_CH = 3
    private const val HV_CH = 4

    // The built-in waves' indices ([WsPresets.WAVES]).
    private const val W_SINE = 0
    private const val W_SQUARE = 3
    private const val W_PULSE_25 = 4
    private const val W_SOFT_SAW = 8
    private const val W_BASS = 9
    private const val W_VOWEL = 12

    /** Noise notes: the LFSR clock is 32 × the note's tone frequency. */
    private val HAT_NOTE = n("C-6")
    private val METAL_NOTE = n("A-4")

    /** The samples' root: they play at their own rate on it. */
    private val ROOT = n("C-4")

    // ---- the original WonderSwan -----------------------------------------------------------------------------------

    private const val M_LEAD = 1
    private const val M_KICK = 2
    private const val M_SNARE = 3
    private const val M_BASS = 4
    private const val M_LASER = 5
    private const val M_HAT = 6
    private const val M_METAL = 7
    private const val M_OPEN_HAT = 8

    private val M_ROOTS = listOf(n("E-2"), n("C-2"), n("G-2"), n("D-2"))

    /** "Paper Crane Run": the original WonderSwan demo (see the object comment). */
    fun mono(): Song {
        val waves = WsPresets.WAVES.map { it.second }
        val own = listOf(
            WsInstrument("Crane lead", WsKind.TONE, wave = W_PULSE_25,
                volume = env(15, 14, 13, 13, 12, 12, 11, 9, 7, 5, 3, 0, release = 7)),
            WsStarterKit.instrument(WsStarterKit.KICK, "Voice kick"),
            WsStarterKit.instrument(WsStarterKit.SNARE, "Voice snare"),
            bass(),
            WsInstrument("Laser", WsKind.TONE, wave = W_SQUARE, volume = env(15, 14, 13, 12, 11, 10, 8, 6, 4, 2, 0), sweep = -24),
            WsInstrument("Hi-hat", WsKind.NOISE, tap = 0, volume = env(11, 7, 4, 2, 0)),
            WsInstrument("Metal", WsKind.NOISE, tap = 4, lfsrReset = true, volume = env(14, 11, 8, 6, 4, 2, 0)),
            WsInstrument("Open hat", WsKind.NOISE, tap = 0, volume = env(12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        )
        return WsSystem.blank().copy(
            title = "Paper Crane Run",
            artist = "Thor Tracker",
            comment = "WonderSwan demo: Wxx lead waves, CPU-fed voice drums on CH2, CH3 sweep lasers and a U/V override, " +
                "CH4 noise tap modes (Nxx). Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(monoIntro(), monoA(), monoB()),
            orders = listOf(0, 1, 2, 1),
            sound = WsSound(false, slots(own), waves, WsStarterKit.samples()),
        )
    }

    private fun bass() = WsInstrument("Round bass", WsKind.TONE, wave = W_BASS,
        volume = env(15, 15, 14, 13, 12, 11, 11, 10, 6, 2, 0, release = 8))

    private fun monoIntro() = PatternBuilder("Intro", WsRules.LANES_MONO).apply {
        for (bar in 0 until 4) {
            val r = bar * 8
            note(BASS_CH, r, M_ROOTS[bar], M_BASS)
            note(NOISE_CH, r + 4, HAT_NOTE, M_HAT)
            if (bar >= 2) note(DRUM_CH, r, ROOT, M_KICK)
        }
        fx(NOISE_CH, 4, 0, FX_PAN, 0x8F)
        note(NOISE_CH, 30, METAL_NOTE, M_METAL)
        fx(NOISE_CH, 30, 0, FX_TAP, 0x02)
        note(DRUM_CH, 28, ROOT, M_SNARE)
        note(DRUM_CH, 30, ROOT, M_SNARE)
        note(LEAD_CH, 24, n("E-4"), M_LEAD)
        note(LEAD_CH, 28, n("G-4"), M_LEAD)
        fx(LEAD_CH, 24, 0, FX_PAN, 0xFB)
        // CH3: a hardware-sweep laser into theme A.
        note(BASS_CH, 30, n("E-5"), M_LASER)
    }.build()

    private fun monoA() = PatternBuilder("Theme A", WsRules.LANES_MONO).apply {
        for (bar in 0 until 4) { monoBass(bar); hats(bar, M_HAT, M_OPEN_HAT); drums(bar, M_KICK, M_SNARE) }
        for ((row, tap) in listOf(14 to 0x01, 30 to 0x03)) {
            note(NOISE_CH, row, METAL_NOTE, M_METAL)
            fx(NOISE_CH, row, 0, FX_TAP, tap)
        }
        lead(M_LEAD, listOf(0 to "B-4", 2 to "G-4", 4 to "E-4", 6 to "G-4", 8 to "C-5", 12 to "B-4", 14 to "A-4",
            16 to "B-4", 18 to "D-5", 20 to "G-5", 24 to "F#5", 26 to "E-5", 28 to "D-5"))
        cut(LEAD_CH, 31)
        note(BASS_CH, 30, n("E-5"), M_LASER)
    }.build()

    private fun monoB() = PatternBuilder("Theme B", WsRules.LANES_MONO).apply {
        for (bar in 0 until 4) { monoBass(bar); hats(bar, M_HAT, M_OPEN_HAT); drums(bar, M_KICK, M_SNARE) }
        // The metal noise walks through the tap modes: each sequence buzzes at its own pitch.
        for ((row, tap) in listOf(6 to 0x01, 14 to 0x03, 22 to 0x05, 30 to 0x07)) {
            note(NOISE_CH, row, METAL_NOTE, M_METAL)
            fx(NOISE_CH, row, 0, FX_TAP, tap)
        }
        // The lead changes waves: square for the first half, the vowel wave for the second (W on every note row: a note
        // row without W returns to the sound's own wave).
        val notes = listOf(0 to "E-5", 4 to "D-5", 6 to "B-4", 8 to "C-5", 12 to "E-5",
            16 to "G-5", 20 to "F#5", 22 to "D-5", 24 to "A-5", 28 to "F#5")
        for ((row, name) in notes) {
            note(LEAD_CH, row, n(name), M_LEAD)
            fx(LEAD_CH, row, 0, FX_WAVE, if (row < 16) W_SQUARE else W_VOWEL)
        }
        fx(LEAD_CH, 24, 1, FX_ARPEGGIO, 0x37)
        cut(LEAD_CH, 31)
        // The last bass note sweeps up through a U/V override of the bass sound (CH3 only).
        note(BASS_CH, 28, n("D-3"), M_BASS)
        fx(BASS_CH, 28, 0, FX_SWEEP, 0x06)
        fx(BASS_CH, 28, 1, FX_SWEEP_TIME, 0x02)
        cut(BASS_CH, 31)
    }.build()

    /** Root, octave, fifth, octave in quarter-row eighths (bars of 8 rows). */
    private fun PatternBuilder.monoBass(bar: Int) {
        val r = bar * 8
        val root = M_ROOTS[bar]
        for ((row, offset) in listOf(0 to 0, 2 to 12, 4 to 7, 6 to 12)) if (r + row < 28 || bar < 3) note(BASS_CH, r + row, root + offset, M_BASS)
    }

    // ---- the WonderSwan Color ----------------------------------------------------------------------------------------

    private const val C_LEAD = 1
    private const val C_KICK = 2
    private const val C_SHAKER = 3
    private const val C_BASS = 4
    private const val C_LASER = 5
    private const val C_HAT = 6
    private const val C_METAL = 7
    private const val C_TOM = 8
    private const val C_SNARE = 9
    private const val C_REVERSE = 10

    /** The Color demo's fifth sample: the kit snare in stereo, its right side 15 ms late. */
    private const val STEREO_SNARE = 4

    private val C_ROOTS = listOf(n("D-2"), n("G-2"), n("A-2"), n("C-2"))

    /** "Lantern Tide": the WonderSwan Color demo (see the object comment). */
    fun color(): Song {
        val waves = WsPresets.WAVES.map { it.second }
        val kit = WsStarterKit.samples()
        val own = listOf(
            WsInstrument("Tide lead", WsKind.TONE, wave = W_SOFT_SAW,
                volume = env(13, 15, 15, 14, 14, 13, 13, 12, 9, 6, 3, 0, release = 8)),
            WsStarterKit.instrument(WsStarterKit.KICK, "DMA kick", WsFeed.DMA),
            WsInstrument("Shaker loop", WsKind.SAMPLE, sample = WsStarterKit.HAT, feed = WsFeed.DMA, rate = WsStarterKit.DMA_RATE, loop = true,
                volume = env(11)),
            bass(),
            WsInstrument("Laser", WsKind.TONE, wave = W_SQUARE, volume = env(15, 14, 13, 12, 11, 10, 8, 6, 4, 2, 0), sweep = -24),
            WsInstrument("Hi-hat", WsKind.NOISE, tap = 0, volume = env(11, 7, 4, 2, 0)),
            WsInstrument("Metal", WsKind.NOISE, tap = 4, lfsrReset = true, volume = env(14, 11, 8, 6, 4, 2, 0)),
            WsStarterKit.instrument(WsStarterKit.TOM, "Voice tom"),
            WsInstrument("Stereo snare", WsKind.SAMPLE, sample = STEREO_SNARE, feed = WsFeed.DMA, rate = WsStarterKit.DMA_RATE),
            WsInstrument("Reverse snare", WsKind.SAMPLE, sample = STEREO_SNARE, feed = WsFeed.DMA, rate = WsStarterKit.DMA_RATE, backwards = true),
        )
        return WscSystem.blank().copy(
            title = "Lantern Tide",
            artist = "Thor Tracker",
            comment = "WonderSwan Color demo: Sound DMA drums on CH2 (a shaker loop gated by Hxx), Hyper Voice with a stereo " +
                "DMA snare, a reversed (backwards) snare and a CPU-fed tom melody, CH3 sweeps and CH4 noise. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(shore(), tideA(), tideB()),
            orders = listOf(0, 1, 2, 1),
            sound = WsSound(true, slots(own), waves, kit + stereoSnare(kit[WsStarterKit.SNARE])),
        )
    }

    /** The kit snare as a stereo sample: left as is, right 90 frames (15 ms at 6000 Hz) later, so it spreads. */
    private fun stereoSnare(snare: WsSample): WsSample {
        val delay = 90
        val n = snare.data.size
        return WsSample("Stereo snare", List(2 * n) { i ->
            val f = i / 2
            if (i % 2 == 0) snare.data[f] else if (f >= delay) snare.data[f - delay] else 128
        }, stereo = true, rate = WsStarterKit.RATE_HZ)
    }

    private fun PatternBuilder.colorBass(bar: Int) {
        val r = bar * 8
        val root = C_ROOTS[bar]
        for ((row, offset) in listOf(0 to 0, 2 to 12, 4 to 10, 6 to 12)) note(BASS_CH, r + row, root + offset, C_BASS)
    }

    /** The shaker loop on CH2 from [row], gated by DMA hold: held on the odd rows, running on the even ones. */
    private fun PatternBuilder.shaker(row: Int, until: Int) {
        note(DRUM_CH, row, ROOT, C_SHAKER)
        for (r in row + 1 until until) fx(DRUM_CH, r, 0, FX_HOLD, if ((r - row) % 2 == 1) 0x01 else 0x00)
    }

    private fun shore() = PatternBuilder("Shore", WsRules.LANES_COLOR).apply {
        for (bar in 0 until 4) {
            note(BASS_CH, bar * 8, C_ROOTS[bar], C_BASS)
            note(NOISE_CH, bar * 8 + 4, HAT_NOTE, C_HAT)
        }
        shaker(0, 30)
        // Hyper Voice: the CPU-fed tom as a melody, panned side to side (Hyper Voice: each side on or off).
        for ((i, pair) in listOf(16 to "A-3", 20 to "C-4", 24 to "D-4", 26 to "E-4", 28 to "G-4").withIndex()) {
            note(HV_CH, pair.first, n(pair.second), C_TOM)
            fx(HV_CH, pair.first, 0, FX_PAN, if (i % 2 == 0) 0xF0 else 0x0F)
        }
        // The reversed stereo snare swells into tide A; starting it takes the DMA from CH2's shaker.
        note(HV_CH, 30, ROOT, C_REVERSE)
        note(LEAD_CH, 24, n("D-4"), C_LEAD)
        note(LEAD_CH, 28, n("F-4"), C_LEAD)
        note(BASS_CH, 30, n("A-5"), C_LASER)
    }.build()

    private fun tideA() = PatternBuilder("Tide A", WsRules.LANES_COLOR).apply {
        for (bar in 0 until 4) {
            colorBass(bar)
            hats(bar, C_HAT, C_HAT)
            val r = bar * 8
            note(DRUM_CH, r, ROOT, C_KICK)
            // The stereo DMA snare on Hyper Voice (the kick has ended by then: one DMA engine).
            note(HV_CH, r + 4, ROOT, C_SNARE)
        }
        note(NOISE_CH, 14, METAL_NOTE, C_METAL)
        fx(NOISE_CH, 30, 0, FX_TAP, 0x06)
        note(NOISE_CH, 30, METAL_NOTE, C_METAL)
        lead(C_LEAD, listOf(0 to "A-4", 2 to "F-4", 4 to "D-4", 6 to "F-4", 8 to "G-4", 12 to "B-4", 14 to "D-5",
            16 to "C-5", 18 to "A-4", 20 to "E-5", 24 to "D-5", 26 to "C-5", 28 to "A-4"))
        fx(LEAD_CH, 0, 0, FX_VIBRATO, 0x23)
        fx(LEAD_CH, 0, 1, FX_VOLUME, 0x0D)
        fx(LEAD_CH, 0, 2, FX_PAN, 0xFC)
        cut(LEAD_CH, 31)
    }.build()

    private fun tideB() = PatternBuilder("Tide B", WsRules.LANES_COLOR).apply {
        for (bar in 0 until 4) { colorBass(bar); hats(bar, C_HAT, C_HAT) }
        // The gated shaker on CH2 throughout; Hyper Voice plays the tom melody fed by the CPU (no DMA needed).
        shaker(0, 28)
        val toms = listOf(0 to "D-4", 3 to "F-4", 6 to "A-4", 8 to "G-4", 11 to "E-4", 14 to "C-4",
            16 to "D-4", 19 to "F-4", 22 to "C-5", 24 to "A-4")
        for ((i, pair) in toms.withIndex()) {
            note(HV_CH, pair.first, n(pair.second), C_TOM)
            fx(HV_CH, pair.first, 0, FX_PAN, if (i % 2 == 0) 0xFF else 0x0F)
        }
        // The reversed snare into tide A again.
        note(HV_CH, 28, ROOT, C_REVERSE)
        // A portamento lead: one keyed note, then glides.
        note(LEAD_CH, 0, n("D-5"), C_LEAD)
        fx(LEAD_CH, 0, 0, FX_VIBRATO, 0x34)
        for ((row, name) in listOf(8 to "F-5", 16 to "E-5", 24 to "A-4")) {
            note(LEAD_CH, row, n(name), 0)
            fx(LEAD_CH, row, 0, FX_PORTAMENTO, 0x10)
        }
        fx(LEAD_CH, 30, 0, FX_PORTAMENTO, 0x00)
        cut(LEAD_CH, 30)
        note(BASS_CH, 30, n("A-5"), C_LASER)
    }.build()

    // ---- shared phrases --------------------------------------------------------------------------------------------

    /** Closed hats in eighths with an open hat on the bar's last eighth. */
    private fun PatternBuilder.hats(bar: Int, closed: Int, open: Int) {
        val r = bar * 8
        for (row in listOf(0, 2, 4)) note(NOISE_CH, r + row, HAT_NOTE, closed)
        note(NOISE_CH, r + 6, HAT_NOTE, open)
    }

    /** Kick on beats 1 and 3, snare on 2 and 4 (bars of 8 rows), CPU-fed voice samples on CH2. */
    private fun PatternBuilder.drums(bar: Int, kick: Int, snare: Int) {
        val r = bar * 8
        note(DRUM_CH, r, ROOT, kick)
        note(DRUM_CH, r + 4, ROOT, snare)
        if (bar % 2 == 1) note(DRUM_CH, r + 6, ROOT, kick)
    }

    private fun PatternBuilder.lead(slot: Int, notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(LEAD_CH, row, n(name), slot)
    }

    // ---- the shortest songs ---------------------------------------------------------------------------------------

    /**
     * The shortest valid WonderSwan song (Color when [color]; the WAV export test's `ws-shortest.wsimg` and
     * `wsc-shortest.wsimg`): one order of a one-row pattern at speed 1, looping to order 0, holding A-4 on CH1 with slot
     * 01, a tone at constant full volume on wave 0 (a sine), so every pass sounds; no samples.
     */
    fun shortest(color: Boolean): Song {
        val system = if (color) WscSystem else WsSystem
        val lanes = WsRules.laneCount(color)
        val held = Cell(note = n("A-4"), instrument = 1)
        val tracks = List(lanes) { ch -> List(Songs.ROWS) { row -> if (ch == LEAD_CH && row == 0) held else Cell.BLANK } }
        return system.blank().copy(
            title = if (color) "WonderSwan Color shortest" else "WonderSwan shortest",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
            sound = WsSound(
                color = color,
                instruments = List(WsRules.SLOTS) { if (it == 0) WsInstrument("Sine", WsKind.TONE, wave = 0) else null },
                waves = listOf(WsPresets.WAVES[W_SINE].second),
            ),
        )
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The demo's own sounds in slots 01.., the tone and noise presets after them, the rest empty. */
    private fun slots(own: List<WsInstrument>): List<WsInstrument?> {
        val waves = WsPresets.WAVES.map { it.second }
        val presets = WsPresets.presets(false).filter { it.sample == null }
            .map { p -> p.instrument.copy(wave = p.wave?.let { waves.indexOf(it) }?.coerceAtLeast(0) ?: 0) }
        val filled = (own + presets).take(WsRules.SLOTS)
        return filled + List(WsRules.SLOTS - filled.size) { null }
    }

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = WsEnvelope(values.toList(), loop, release)

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String, lanes: Int) {
        private val tracks = List(lanes) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = Cell.CUT, instrument = 0)
        }

        fun fx(channel: Int, row: Int, column: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].withEffect(column, effect, param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() }, length = ROWS)
    }
}
