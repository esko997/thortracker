package org.thortracker.system.ws

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.WsLowering
import org.thortracker.backend.WsPlayerImage
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.BankSpec
import org.thortracker.system.ChannelKind
import org.thortracker.system.ChannelSpec
import org.thortracker.system.EffectSpec
import org.thortracker.system.ExportFormat
import org.thortracker.system.FinePitch
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.SharedState
import org.thortracker.system.SongSetting
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * Bandai's WonderSwan (1999): the sound unit inside its "ASWAN" SoC, played by Thor Tracker's own player (native
 * `"ws-wonderswan"`; music-only, exported as VGM): four lanes CH1-CH4, each a 32-step 4-bit wavetable with 4-bit
 * volumes per side; CH2 can play 8-bit samples (its voice output, streamed by the CPU from the voice timer), CH3 has a
 * hardware frequency sweep, CH4 an LFSR noise generator. See [WonderSwanSystemBase] and docs/AUDIO_ENGINE.md
 * ("WonderSwan").
 */
object WsSystem : WonderSwanSystemBase(color = false) {
    override val id = WsPlayerImage.ENGINE_ID_MONO
    override val name = "WonderSwan"

    override fun demo(): Song = WsDemoSong.mono()
}

/**
 * The WonderSwan Color (2000, SoC "SPHINX"; the SwanCrystal's "SPHINX2" has the same sound unit): the original's four
 * lanes plus **Hyper Voice** (a headphone-only 8-bit sample channel, mono or stereo) and **Sound DMA** (one engine
 * streaming sample bytes to CH2's voice or to Hyper Voice at 4000, 6000, 12000 or 24000 bytes per second). Native
 * `"ws-wonderswan-color"`.
 */
object WscSystem : WonderSwanSystemBase(color = true) {
    override val id = WsPlayerImage.ENGINE_ID_COLOR
    override val name = "WonderSwan Color"

    override fun demo(): Song = WsDemoSong.color()
}

/**
 * What both WonderSwan models share: one bank of [SLOTS] sounds (tone, noise or sample, with volume, arpeggio, pitch
 * and wave/tap envelopes), the song's waves and samples, up to four effect columns per lane, and the console's shared
 * speaker shift and voice timer ([WsSound]). [color] adds the Hyper Voice lane, Sound DMA and the `H` effect. A song's
 * model is its [WsSound.color]; the two are separate systems so no original-model song can name a Color-only facility.
 */
abstract class WonderSwanSystemBase internal constructor(val color: Boolean) : SoundSystem {
    override val family = "WonderSwan"

    val SLOTS: Int get() = WsRules.SLOTS

    override val channels: List<ChannelSpec> = WsRules.laneNames(color).mapIndexed { lane, name ->
        // CH1-CH4 are wavetable channels (CH4's noise sounds are pitched by the note too); Hyper Voice plays samples
        // at pitched (CPU-fed) or fixed (DMA) rates.
        ChannelSpec(name, if (lane == WsRules.HYPER_VOICE) ChannelKind.VOICE else ChannelKind.WAVE, 0, tonal = true)
    }
    override val banks = listOf(BankSpec("Sounds", WsRules.SLOTS))
    override val noteRange = WsRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = WsRules.effects(color)

    /** Every shared view plus the Wave view (the song's 32-step waves). */
    override val views = ViewKind.entries.toSet()
    override val exports = listOf(ExportFormat("vgm") { WsVgm.export(it) })
    override val fine: FinePitch = WsFine
    override val help: SystemHelp = WsHelp(color)

    /** OFF is a note-column event (release or stop). */
    override val offEffect: String? = null

    /** One player tick per display frame: 3072000 / 40704 = 75.47 Hz. */
    override val tickHz = WsRules.TICK_HZ
    override val maxOrders = WsPlayerImage.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = WsSound.blank(color),
    )

    override val soundSerializer: KSerializer<WsSound> = WsSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    /** The effects whose hardware [channel] has: `N` on CH4, `U V` on CH3, `W` on CH1-CH4, `H` on CH2 and Hyper Voice (Color). */
    override fun effectsFor(channel: Int): List<EffectSpec> = WsRules.effectsFor(channel, color)

    override fun effectColumns(channel: Int): Int = WsRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = WsRules.describe(song, channel, effect, param)

    /** Arpeggios read with the notes they step through. */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        WsEffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = WsValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = WsValidator.structuralErrors(song, this)
    override fun errors(song: Song) = WsValidator.errors(song, this)
    override fun warnings(song: Song) = WsValidator.warnings(song, this)

    /**
     * Parked orders removed; the loop order and every `Bxx` (in any effect column; `Bxx` = order xx) renumbered to the
     * played orders. A target that is parked lands on the next played order after it (the loop order past the last).
     */
    override fun arranged(song: Song): Song {
        if (song.parkedOrders.isEmpty()) return song
        val played = Songs.playedOrders(song)
        if (played.isEmpty()) return song
        val next = IntArray(song.orders.size + 1) { -1 }
        var k = played.size
        for (o in song.orders.indices.reversed()) {
            if (k > 0 && played[k - 1] == o) k--
            next[o] = if (k < played.size) k else -1
        }
        val loop = next[song.loopOrder].let { if (it >= 0) it else 0 }
        fun target(order: Int) = if (order in song.orders.indices) next[order].let { if (it >= 0) it else loop } else order
        val patterns = song.patterns.map { p ->
            val tracks = p.tracks.map { track -> track.map { c -> jumpTarget(c)?.let { withJumpTarget(c, target(it)) } ?: c } }
            if (tracks == p.tracks) p else p.copy(tracks = tracks)
        }
        return song.copy(orders = played.map { song.orders[it] }, loopOrder = loop, patterns = patterns, parkedOrders = emptyList())
    }

    /** The order at [order] alone, looping: every `Bxx` in it aims at that one order (`B00`). */
    override fun patternLoop(song: Song, order: Int): Song {
        val index = song.orders[order.coerceIn(0, song.orders.size - 1)]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        val patterns = if (tracks == pattern.tracks) song.patterns else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList())
    }

    /** `Bxx` jumps to order xx (absolute, B00 = the first order); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == WsRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == WsRules.FX_JUMP) out = out.withEffect(c, WsRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine()

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..WsRules.SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Sound ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..WsRules.SLOTS) null else instrument(song, slot)?.let { summary(song.ws, it) } ?: "Empty: choose a preset"

    /** A tone sound's wave (its fixed one; the first step of its wave sequence when it has one) as levels 0..1. */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0) return null
        val ins = instrument(song, slot)?.takeIf { it.kind == WsKind.TONE } ?: return null
        val wave = ins.waves.values.firstOrNull() ?: ins.wave
        return song.ws.waves.getOrNull(wave)?.map { it / WsSound.WAVE_MAX.toFloat() }
    }

    /** What [instrumentName] shows for an empty slot. */
    val EMPTY_NAME: String get() = "(empty)"

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): WsInstrument? = song.ws.instrument(slot)

    /**
     * One line about [ins]: "Tone · wave 03 · sweep +5 every 2/375 s · volume envelope", "Noise (CH4) · tap 2 (254
     * steps)", "Sample 00 Kick · 1500 frames · CPU-fed, root C-4 · one-shot", "Sample 01 Loop · DMA 12000 Hz · loop".
     */
    fun summary(sound: WsSound, ins: WsInstrument): String {
        val envelopes = buildList {
            if (!ins.volume.isEmpty) add("volume")
            if (!ins.isDma && !ins.arpeggio.isEmpty) add("arpeggio")
            if (!ins.isDma && !ins.pitch.isEmpty) add(if (ins.pitchRelative) "relative pitch" else "pitch")
            if (ins.kind == WsKind.TONE && !ins.waves.isEmpty) add("wave")
            if (ins.kind == WsKind.NOISE && !ins.taps.isEmpty) add("tap")
        }
        val head = when (ins.kind) {
            WsKind.TONE -> "Tone · wave ${NumberText.hex(ins.wave, 2)}" +
                if (ins.sweep != 0) " · CH3 sweep ${WsRules.signed(ins.sweep)} every ${ins.sweepTime + 1}/375 s" else ""
            WsKind.NOISE -> "Noise (CH4) · tap ${ins.tap} (${WsRules.TAP_LENGTHS[ins.tap.coerceIn(0, 7)]} steps)" +
                if (ins.lfsrReset) " · LFSR reset" else ""
            WsKind.SAMPLE -> {
                val s = sound.samples.getOrNull(ins.sample)
                "Sample ${NumberText.hex(ins.sample, 2)}" + (s?.let { " ${it.name} · ${it.frames} frames${if (it.stereo) " stereo" else ""}" } ?: " (missing)") +
                    (if (ins.isDma) " · DMA ${WsRules.DMA_RATES[ins.rate.coerceIn(0, 3)]} Hz · " + (if (ins.loop) "loop" else "one-shot") +
                        (if (ins.backwards) " · backwards" else "")
                    else " · CPU-fed, root ${noteName(ins.root)} · " + (if (ins.loop) "loop from ${ins.loopStart}" else "one-shot"))
            }
        }
        return if (envelopes.isEmpty()) head else "$head · ${envelopes.joinToString(", ")} envelope${if (envelopes.size > 1) "s" else ""}"
    }

    /** A tone sound points the Wave view at its wave. */
    override fun selectSound(selection: Selection, song: Song): Selection {
        if (selection.bank != 0) return selection
        val ins = instrument(song, selection.instrument)?.takeIf { it.kind == WsKind.TONE } ?: return selection
        return if (ins.wave in song.ws.waves.indices) selection.copy(wave = ins.wave) else selection
    }

    override fun view(kind: ViewKind): SystemView? = when (kind) {
        ViewKind.INSTRUMENT -> WsInstrumentView
        ViewKind.WAVE -> WsWaveView
        else -> null
    }

    /**
     * A one-shot sample note on CH2 or Hyper Voice ends by itself: a CPU-fed sample after its frames at the note's step
     * and the song's voice timer (header reload; `Txx` changes are not followed), a DMA sample after its bytes at its
     * rate; over the rows' speeds from the song's ticks per row and `Fxx` on any lane of the pattern (earlier orders'
     * speed changes are not followed). Null for other notes, looping samples, a sound that does not fit the lane or a
     * note that outlasts the pattern.
     */
    override fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? {
        if (channel != WsRules.VOICE_LANE && channel != WsRules.HYPER_VOICE) return null
        val p = song.patterns.getOrNull(pattern) ?: return null
        val track = p.tracks.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row)?.takeIf { it.note >= 0 } ?: return null
        val slot = if (cell.instrument > 0) cell.instrument else (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } } ?: return null
        val sound = song.ws
        val ins = sound.instrument(slot)?.takeIf { it.kind == WsKind.SAMPLE && !it.loop } ?: return null
        val sample = sound.samples.getOrNull(ins.sample) ?: return null
        if (!WsValidator.fits(sound, ins, channel)) return null
        val ticks = if (ins.isDma) {
            val bytes = sample.data.size.toLong()
            kotlin.math.ceil(bytes * WsRules.TICK_HZ / WsRules.DMA_RATES[ins.rate.coerceIn(0, 3)]).toInt()
        } else {
            val step = WsRules.sampleStep(cell.note * 64, ins.root)
            if (step <= 0L) return null
            val interrupts = ((sample.frames - 1).toLong() * 65536 + step - 1) / step + 1
            kotlin.math.ceil(interrupts * WsRules.TICK_HZ / WsRules.timerHz(sound.timerReload)).toInt()
        }
        fun speedAfter(r: Int, speed: Int): Int {
            var s = speed
            for (t in p.tracks) {
                val c = t[r]
                for (col in 0..c.more.size) if (c.effectCode(col) == WsRules.FX_SPEED && c.effectParam(col) > 0) s = c.effectParam(col)
            }
            return s
        }
        var speed = song.ticksPerRow
        for (r in 0 until row) speed = speedAfter(r, speed)
        var left = ticks
        var rows = 0
        var r = row
        while (left > 0 && r < p.length) {
            speed = speedAfter(r, speed)
            left -= speed
            rows++
            r++
        }
        return if (left > 0) null else rows
    }

    override val songSettings: List<SongSetting> get() = WsSongSettings.ALL

    /** The voice timer, the speaker shift and (Color) the lane owning Sound DMA where straight play reaches ([WsSharedState]). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState = WsSharedState.state(song, order, row)

    private inner class Engine : PlaybackEngine {
        override val id: String get() = this@WonderSwanSystemBase.id
        override val driver = "Thor Tracker's WonderSwan player on a C port of the ares WonderSwan APU (ares 038bcb9, " +
            "cycle-accurate mode, 3.072 MHz), blip_buf 1.1.0"
        override val imageSize = WsPlayerImage.IMAGE_SIZE

        /** At 75.5 ticks per second, a minute of song. */
        override val seekTicks = WsPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = WsLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val sound = song.ws
            val ins = if (instrument in 1..WsRules.SLOTS) sound.instrument(instrument) else null
            return when {
                instrument !in 1..WsRules.SLOTS -> "Choose a sound (01-${NumberText.hex(WsRules.SLOTS, 2)}) to audition"
                ins == null -> "Sound ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices -> "Cannot audition on lane $channel"
                note !in noteRange -> "${noteName(note)} is outside ${noteName(noteRange.first)}..${noteName(noteRange.last)}, the notes the player plays"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> WsValidator.instrumentProblem(sound, instrument)?.let { "Sound ${NumberText.hex(instrument, 2)} \"${ins.name}\" $it" }
                    ?: WsValidator.laneProblem(sound, ins, channel)?.let { "\"${ins.name}\" $it; audition it there" }
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }
}

/**
 * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column: the
 * player's pitch unit is 1/64 semitone; the chip's 11-bit period then rounds it (within 6.4 cents up to C-6, 23.3
 * cents at worst). DMA samples play at their fixed rate and ignore it.
 */
private object WsFine : FinePitch {
    override val name = "Pxx"
    override val max = 63
    override fun range(note: Int): IntRange = 0..max
    override fun cents(note: Int, fine: Int): Double = fine * 100.0 / 64

    /** The `P` column's upward fine value; 0 without one; null for a downward `P` (not a tuning). */
    override fun of(cell: Cell): Int? {
        val col = column(cell) ?: return 0
        val p = cell.effectParam(col)
        return if (p >= WsRules.FINE_CENTRE) p - WsRules.FINE_CENTRE else null
    }

    override fun write(cell: Cell, note: Int, fine: Int): Cell? {
        val current = column(cell)
        val base = cell.copy(note = note)
        if (fine == 0) {
            return if (current != null && cell.effectParam(current) >= WsRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
        }
        if (current != null && cell.effectParam(current) < WsRules.FINE_CENTRE) return null
        val col = current ?: (0 until WsRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
        return base.withEffect(col, WsRules.FX_FINE, WsRules.FINE_CENTRE + fine)
    }

    private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == WsRules.FX_FINE }
}

/** The Help view's WonderSwan prose: the hardware facts as the chip has them, for the [color] model or the original. */
private class WsHelp(private val color: Boolean) : SystemHelp {
    override val off = "Release: sounds whose volume envelope has a release point play on from it; other sounds stop at once"
    override val offEntered = "OFF: release (or stop without a release point)"
    override val offInspector = "OFF: release, or stop without a volume release point"
    override val effectsIntro: List<String> = listOfNotNull(
        "Played by Thor Tracker's own player on a port of the ares emulator's WonderSwan sound unit: one tick per display " +
            "frame, 75.47 ticks per second.",
        "CH1-CH4 play the song's 32-step, 4-bit waves (drawn in the Wave view). The chip's 11-bit period sets the pitch " +
            "(96000 / N Hz): A-4 sounds at 440.37 Hz, notes are within 6.4 cents up to C-6 and 16.4 cents up to C-7, and the " +
            "worst note, F#-7, is 23.3 cents off. The lowest note is G-1.",
        "CH2 can also play 8-bit samples through its voice output: three levels per side (off, 50 %, 100 %; the lane volume " +
            "picks them: 1-11 is 50 %, 12-15 full). The player feeds them from the CPU's voice timer, 12000 / T Hz (Song " +
            "view, Txx); a sample sound's root note plays one frame per interrupt, other notes faster or slower.",
        "CH3 has a hardware sweep: a sound's sweep value (or Uxx) is added to the frequency register every (time + 1) / 375 s " +
            "(or Vxx), wrapping around the 11-bit register. Other lanes ignore a sound's sweep.",
        "CH4 can play noise: a 15-bit LFSR clocked at 32 times the note's tone frequency, with eight tap modes whose " +
            "sequences are 32767, 1953, 254, 217, 73, 63, 42 and 28 steps long. Short sequences buzz at a pitch of " +
            "3072000 / (N × length) Hz; the tap mode is the sound's (or Nxx), and LFSR reset restarts it at each note.",
        if (color) "WonderSwan Color only: Hyper Voice, a fifth lane playing 8-bit samples, mono or stereo, heard on " +
            "headphones only, at four levels (100, 50, 25, 12.5 %) with each side on or off; and Sound DMA, one engine " +
            "streaming a sample to CH2 or Hyper Voice at 4000, 6000, 12000 or 24000 bytes per second (not pitched: the note " +
            "only triggers it; one-shot, looping or backwards; Hxx holds it). A DMA sample starting on one lane stops the " +
            "one sounding on the other (the lane tags show who has the DMA)."
        else "The original WonderSwan has no Hyper Voice and no Sound DMA: those are the WonderSwan Color's (a separate " +
            "system in New song).",
        "Output (Song view): headphones are stereo (the original model needs Bandai's headphone adapter); the speaker is " +
            "mono, the lanes' sum shifted by the speaker shift (Kxx: 100, 50, 25 or 12.5 %) and wrapped to 8 bits, so low " +
            "shifts are loud but distort when several lanes play loudly." + if (color) " Hyper Voice is silent on the speaker." else "",
        "Up to four effect columns per lane, run in column order. Volume (Cxx, envelope) and pan (8xy) act from their row " +
            "on; Pxx detunes until the next note.",
        "No chords: each lane is one voice, so spread a chord over lanes.",
    )
    override val tonalChannels = if (color) "every lane" else "all four channels"
    override val tonalChannelsShort = if (color) "CH1-4/HV" else "CH1-4"
    override val untonalNote = "Every lane is tonal; on noise the note sets the LFSR's clock, on a CPU-fed sample its speed; DMA samples ignore it."
    override val sketchChannels = "CH1-CH4"

    override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
        WsInstrumentView.helpSections(keys, color) + WsWaveView.helpSections(keys)

    override fun limits(song: Song): List<Pair<String, String>> = WsValidator.limits(song)
}

/** The editor's longer reading of an arpeggio (`0xy`): the notes it steps through from the row's (or the sounding) note. */
private object WsEffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        if (code != WsRules.FX_ARPEGGIO || param == 0) return null
        val x = param shr 4
        val y = param and 0x0F
        val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
        return listOf(root, root + x, root + y).joinToString(" → ") { name(song, it) } + " every tick"
    }

    /** The note sounding at [row] of [track] from the rows above it (the pattern only), or null after an OFF or none. */
    private fun soundingNote(track: List<Cell>, row: Int): Int? {
        for (r in row - 1 downTo 0) {
            val n = track[r].note
            if (n >= 0) return n
            if (n == Cell.CUT) return null
        }
        return null
    }

    private fun name(song: Song, note: Int): String = if (note in WsRules.NOTES) song.system.noteName(note) else "(out of range)"
}

/**
 * The Song view's WonderSwan rows: the output the song is heard through, and the console's shared state at the song
 * start — the speaker shift and the voice timer. Effects (`K T`) change the last two while the song plays.
 */
object WsSongSettings {
    private fun update(song: Song, f: (WsSound) -> WsSound): Song = f(song.ws).let { if (it == song.ws) song else song.copy(sound = it) }

    val ALL: List<SongSetting> = listOf(
        SongSetting("Output", "Headphones or speaker", "Set output",
            value = { s ->
                when (s.ws.output) {
                    WsOutput.HEADPHONES -> "Headphones   stereo" +
                        if (s.ws.color) ", Hyper Voice included" else " (the original WonderSwan needs Bandai's headphone adapter)"
                    WsOutput.SPEAKER -> "Speaker   mono, the sum shifted by the speaker shift and wrapped to 8 bits" +
                        if (s.ws.color) "; Hyper Voice is silent" else ""
                }
            },
            adjust = { s, f, c ->
                if ((f + c) % 2 == 0) s else update(s) { it.copy(output = if (it.output == WsOutput.HEADPHONES) WsOutput.SPEAKER else WsOutput.HEADPHONES) }
            },
            reset = { s -> update(s) { it.copy(output = WsOutput.HEADPHONES) } },
        ),
        SongSetting("Speaker shift", "Speaker share of the sum", "Set speaker shift",
            value = { s ->
                val k = s.ws.speakerShift
                "$k   ${WsRules.shiftText(k)} of the lanes' sum before the speaker's 8-bit wrap (Kxx; headphones ignore it)"
            },
            adjust = { s, f, c -> update(s) { it.copy(speakerShift = (it.speakerShift + f + c).coerceIn(0, WsPlayerImage.MAX_SHIFT)) } },
            reset = { s -> update(s) { it.copy(speakerShift = WsSound.DEFAULT_SHIFT) } },
        ),
        SongSetting("Voice timer", "CPU-fed sample rate", "Set voice timer",
            value = { s ->
                val t = s.ws.timerReload
                "${NumberText.hex(t, 2)}   12000 / $t = ${WsRules.timerText(t)}: one CPU-fed sample frame per interrupt at the root note (Txx)"
            },
            adjust = { s, f, c -> update(s) { it.copy(timerReload = (it.timerReload + f + 16 * c).coerceIn(1, WsPlayerImage.MAX_TIMER)) } },
            reset = { s -> update(s) { it.copy(timerReload = WsSound.DEFAULT_TIMER) } },
        ),
    )
}

/**
 * The console's shared state at a song position, as straight play reaches it: the song's start values, then every
 * `T` and `K` in the order the player runs them — orders that play (a parked order only on its own), rows, then within
 * a row lanes in order at tick 0 and delayed rows (`Gxx`) at their tick, effect columns in order, so the last one wins;
 * a delay the row's speed never reaches does not run. On the Color it also follows which lane owns Sound DMA: a DMA
 * sample keyed on CH2 or Hyper Voice takes it; that lane's next note, or its OFF (`Sxx` included) when the sound has no
 * volume release point, gives it up. Jumps and breaks are not followed (the walk is sequential, as the validator's).
 * Out-of-range parameters (rejected by the loader) are skipped.
 */
object WsSharedState {
    /** The shared values: voice timer reload, speaker shift, and the lane owning Sound DMA (-1 none). */
    data class Shared(val timer: Int, val shift: Int, val dmaOwner: Int)

    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /** The song start: the header's values, no DMA owner. */
    fun start(sound: WsSound) = Shared(sound.timerReload, sound.speakerShift, -1)

    /** The shared state after row [row] of order [order] has run (the song start when [order] does not exist). */
    fun at(song: Song, order: Int, row: Int): Shared {
        val sound = song.ws
        var s = start(sound)
        if (order !in song.orders.indices) return s
        val lanes = WsRules.laneCount(sound.color)
        var speed = song.ticksPerRow
        val delays = IntArray(lanes)
        val slots = IntArray(lanes)
        val sounding = BooleanArray(lanes)
        val cuts = IntArray(lanes) { -1 }

        fun stop(lane: Int) {
            sounding[lane] = false
            if (s.dmaOwner == lane) s = s.copy(dmaOwner = -1)
        }

        fun off(lane: Int) {
            if (!sounding[lane]) return
            val ins = sound.instrument(slots[lane])
            if (ins == null || ins.volume.release < 0) stop(lane)
        }

        fun run(lane: Int, c: Cell) {
            if (c.instrument != 0) slots[lane] = c.instrument
            for (col in 0..c.more.size) {
                val param = c.effectParam(col)
                when (c.effectCode(col)) {
                    WsRules.FX_TIMER -> if (param in 1..WsPlayerImage.MAX_TIMER) s = s.copy(timer = param)
                    WsRules.FX_SHIFT -> if (param <= WsPlayerImage.MAX_SHIFT) s = s.copy(shift = param)
                    WsRules.FX_CUT -> cuts[lane] = param
                }
            }
            when {
                c.note == Cell.CUT -> off(lane)
                c.note >= 0 -> {
                    // A note on a lane that holds the DMA keys on (the sounding note is a DMA sample, never a glide).
                    if (s.dmaOwner == lane) s = s.copy(dmaOwner = -1)
                    val ins = sound.instrument(slots[lane])
                    if (ins != null && WsValidator.fits(sound, ins, lane)) {
                        sounding[lane] = true
                        if (ins.isDma) s = s.copy(dmaOwner = lane)
                    }
                }
            }
        }

        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns.getOrNull(song.orders[o]) ?: continue
            if (p.tracks.size != lanes) continue
            val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
            for (r in 0..last) {
                for (ch in 0 until lanes) {
                    val c = p.tracks[ch][r]
                    for (col in 0..c.more.size) if (c.effectCode(col) == WsRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                cuts.fill(-1)
                for (ch in 0 until lanes) {
                    val c = p.tracks[ch][r]
                    var d = 0
                    for (col in 0..c.more.size) if (c.effectCode(col) == WsRules.FX_DELAY && c.effectParam(col) > 0) d = c.effectParam(col)
                    delays[ch] = d
                    if (d == 0) run(ch, p.tracks[ch][r])
                }
                for (tick in 0 until speed) {
                    if (tick > 0) for (ch in 0 until lanes) if (delays[ch] == tick) run(ch, p.tracks[ch][r])
                    for (ch in 0 until lanes) if (cuts[ch] == tick) off(ch)
                }
            }
        }
        return s
    }

    /**
     * [at] as the Pattern view shows it: "dma" on the lane owning Sound DMA, and the inspector's lines (voice timer,
     * speaker shift, and on the Color the DMA owner). Cached per position.
     */
    fun state(song: Song, order: Int, row: Int): SharedState {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val s = at(song, order, row)
        val color = song.ws.color
        val names = WsRules.laneNames(color)
        val tags = List(names.size) { if (it == s.dmaOwner) "dma" else null }
        val lines = listOfNotNull(
            "Voice timer ${WsRules.timerText(s.timer)} (T${NumberText.hex(s.timer, 2)})",
            "Speaker shift ${s.shift} (${WsRules.shiftText(s.shift)})",
            if (color) "Sound DMA: " + (names.getOrNull(s.dmaOwner) ?: "idle") else null,
        )
        val state = SharedState(tags, lines)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }
}
