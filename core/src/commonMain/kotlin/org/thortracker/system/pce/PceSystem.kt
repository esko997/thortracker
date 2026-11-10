package org.thortracker.system.pce

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.PceLowering
import org.thortracker.backend.PcePlayerImage
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
 * The NEC PC Engine / TurboGrafx-16 sound generator, the PSG inside Hudson's HuC6280, played by Thor Tracker's own
 * player on Mednafen's PSG (native `"pce-huc6280"`; music-only, exported as VGM): six channels, each a 32-step 5-bit
 * wavetable, noise on PSG 5 and PSG 6, or a direct D/A streaming samples from the CPU timer; up to four effect columns
 * per channel and one bank of [SLOTS] sounds (tone, noise or sample, with volume, arpeggio, pitch and wave envelopes).
 * The chip's master balance, its one LFO (PSG 2's wave bending PSG 1's pitch) and the DDA timer are song data
 * ([PceSound]) changed by shared effects; see docs/AUDIO_ENGINE.md ("PC Engine").
 */
object PceSystem : SoundSystem {
    override val id = PcePlayerImage.ENGINE_ID
    override val name = "PC Engine (HuC6280)"

    const val SLOTS = PcePlayerImage.MAX_INSTRUMENTS

    override val channels = PceRules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.WAVE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Sounds", SLOTS))
    override val noteRange = PceRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = PceRules.EFFECTS

    /** Every shared view plus the Wave view (the song's 32-step waves). */
    override val views = ViewKind.entries.toSet()
    override val exports = listOf(ExportFormat("vgm") { PceVgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is a note-column event (release or stop). */
    override val offEffect: String? = null

    /** One player tick per 60 Hz frame at multispeed 1. */
    override val tickHz = 60.0
    override val maxOrders = PcePlayerImage.MAX_ORDERS

    override fun ticksPerSecond(song: Song): Double = tickHz * song.pce.multispeed

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = PceSound.blank(),
    )

    override val soundSerializer: KSerializer<PceSound> = PceSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = PceDemoSong.song()

    /** Every effect on every channel (noise is a sound kind, checked with the sound). */
    override fun effectsFor(channel: Int): List<EffectSpec> = PceRules.EFFECTS

    override fun effectColumns(channel: Int): Int = PceRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = PceRules.describe(song, effect, param)

    /** Arpeggios read with the notes they step through. */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        EffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = PceValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = PceValidator.structuralErrors(song)
    override fun errors(song: Song) = PceValidator.errors(song)
    override fun warnings(song: Song) = PceValidator.warnings(song)

    /**
     * Parked orders removed; the loop order and every `Bxx` (in any effect column; `Bxx` = order xx) renumbered to
     * the played orders. A target that is parked lands on the next played order after it (the loop order past the last).
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

    /**
     * The order at [order] alone, looping: every `Bxx` in it aims at that one order (`B00`); `Dxx` already lands on
     * "the next order", which is the loop.
     */
    override fun patternLoop(song: Song, order: Int): Song {
        val index = song.orders[order.coerceIn(0, song.orders.size - 1)]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        val patterns = if (tracks == pattern.tracks) song.patterns else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList())
    }

    /** `Bxx` jumps to order xx (absolute, B00 = the first order); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == PceRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == PceRules.FX_JUMP) out = out.withEffect(c, PceRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Sound ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.let { summary(song.pce, it) } ?: "Empty: choose a preset"

    /** A tone sound's wave (its fixed one; the first step of its wave envelope when it has one) as levels 0..1. */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0) return null
        val ins = instrument(song, slot)?.takeIf { it.kind == PceKind.TONE } ?: return null
        val wave = ins.waves.values.firstOrNull() ?: ins.wave
        return song.pce.waves.getOrNull(wave)?.map { it / PceSound.WAVE_MAX.toFloat() }
    }

    /** What [instrumentName] shows for an empty slot. */
    const val EMPTY_NAME = "(empty)"

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): PceInstrument? = song.pce.instrument(slot)

    /**
     * One line about [ins]: "Tone · wave 03 · volume, arpeggio envelopes", "Noise (PSG 5-6) · note picks the rate",
     * "Sample 00 Kick · 1747 frames · root C-4 · one-shot".
     */
    fun summary(sound: PceSound, ins: PceInstrument): String {
        val envelopes = buildList {
            if (!ins.volume.isEmpty) add("volume")
            if (!ins.arpeggio.isEmpty) add("arpeggio")
            if (!ins.pitch.isEmpty) add(if (ins.pitchRelative) "relative pitch" else "pitch")
            if (ins.kind == PceKind.TONE && !ins.waves.isEmpty) add("wave")
        }
        val head = when (ins.kind) {
            PceKind.TONE -> "Tone · wave ${NumberText.hex(ins.wave, 2)}"
            PceKind.NOISE -> "Noise (PSG 5-6) · the note picks the rate"
            PceKind.SAMPLE -> {
                val s = sound.samples.getOrNull(ins.sample)
                "Sample ${NumberText.hex(ins.sample, 2)}" + (s?.let { " ${it.name} · ${it.data.size} frames" } ?: " (missing)") +
                    " · root ${noteName(ins.root)} · " + (if (ins.loop) "loop from ${ins.loopStart}" else "one-shot")
            }
        }
        return if (envelopes.isEmpty()) head else "$head · ${envelopes.joinToString(", ")} envelope${if (envelopes.size > 1) "s" else ""}"
    }

    /** A tone sound points the Wave view at its wave. */
    override fun selectSound(selection: Selection, song: Song): Selection {
        if (selection.bank != 0) return selection
        val ins = instrument(song, selection.instrument)?.takeIf { it.kind == PceKind.TONE } ?: return selection
        return if (ins.wave in song.pce.waves.indices) selection.copy(wave = ins.wave) else selection
    }

    override fun view(kind: ViewKind): SystemView? = when (kind) {
        ViewKind.INSTRUMENT -> PceInstrumentView
        ViewKind.WAVE -> PceWaveView
        else -> null
    }

    /**
     * A one-shot sample note ends by itself: the rows its frames last at the note's step and the song's DDA rate
     * (header timer; `Txx` changes are not followed), over the rows' speeds from the song's ticks per row and `Fxx` on
     * any channel of the pattern (earlier orders' speed changes are not followed). Null for other notes, looping
     * samples or a note that outlasts the pattern.
     */
    override fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? {
        val p = song.patterns.getOrNull(pattern) ?: return null
        val track = p.tracks.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row)?.takeIf { it.note >= 0 } ?: return null
        val slot = if (cell.instrument > 0) cell.instrument else (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } } ?: return null
        val sound = song.pce
        val ins = sound.instrument(slot)?.takeIf { it.kind == PceKind.SAMPLE && !it.loop } ?: return null
        val frames = sound.samples.getOrNull(ins.sample)?.data?.size ?: return null
        val step = PceRules.sampleStep(cell.note * 64, ins.root)
        if (step <= 0L) return null
        val interrupts = ((frames - 1).toLong() * 65536 + step - 1) / step + 1
        val ticks = kotlin.math.ceil(interrupts * ticksPerSecond(song) / PceRules.timerHz(sound.timerReload)).toInt()
        fun speedAfter(r: Int, speed: Int): Int {
            var s = speed
            for (t in p.tracks) {
                val c = t[r]
                for (col in 0..c.more.size) if (c.effectCode(col) == PceRules.FX_SPEED && c.effectParam(col) > 0) s = c.effectParam(col)
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

    override val songSettings: List<SongSetting> get() = PceSongSettings.ALL

    /** The chip's master balance, LFO and DDA timer where straight play reaches ([PceSharedState]). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState = PceSharedState.state(song, order, row)

    private object Engine : PlaybackEngine {
        override val id = PcePlayerImage.ENGINE_ID
        override val driver = "Thor Tracker's PC Engine player on Mednafen 1.32.1's PSG (HuC6280, 3.58 MHz), blip_buf 1.1.0"
        override val imageSize = PcePlayerImage.IMAGE_SIZE

        /** At 60 ticks per second, a minute of song. */
        override val seekTicks = PcePlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = PceLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val sound = song.pce
            val ins = if (instrument in 1..SLOTS) sound.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose a sound (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Sound ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices -> "Cannot audition on channel $channel"
                note !in noteRange -> "${noteName(note)} is outside ${noteName(noteRange.first)}..${noteName(noteRange.last)}, the notes the player plays"
                ins.kind == PceKind.NOISE && channel !in PceRules.NOISE_CHANNELS ->
                    "Noise needs PSG 5 or 6; audition \"${ins.name}\" there"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> PceValidator.instrumentProblem(sound, instrument)?.let { "Sound ${NumberText.hex(instrument, 2)} \"${ins.name}\" $it" }
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column: the
     * player's pitch unit is 1/64 semitone, so every degree is requested within 0.8 cents; the chip's 12-bit period
     * then rounds it (within 7.4 cents up to C-6, 19.2 cents at B-7).
     */
    private object Fine : FinePitch {
        override val name = "Pxx"
        override val max = 63
        override fun range(note: Int): IntRange = 0..max
        override fun cents(note: Int, fine: Int): Double = fine * 100.0 / 64

        /** The `P` column's upward fine value; 0 without one; null for a downward `P` (not a tuning). */
        override fun of(cell: Cell): Int? {
            val col = column(cell) ?: return 0
            val p = cell.effectParam(col)
            return if (p >= PceRules.FINE_CENTRE) p - PceRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= PceRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < PceRules.FINE_CENTRE) return null
            val col = current ?: (0 until PceRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, PceRules.FX_FINE, PceRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == PceRules.FX_FINE }
    }

    private object Help : SystemHelp {
        override val off = "Release: sounds whose volume envelope has a release point play on from it; other sounds stop at once"
        override val offEntered = "OFF: release (or stop without a release point)"
        override val offInspector = "OFF: release, or stop without a volume release point"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player on Mednafen's HuC6280 PSG: 60 ticks per second times the song's multispeed.",
            "Each channel plays one of three kinds of sound. Tone: one of the song's 32-step, 5-bit waves (drawn in the Wave " +
                "view), at the note's pitch; a wave envelope can switch waves every tick, Wxx changes the wave of a tone sound " +
                "without one. Noise: the noise generator, on PSG 5 and PSG 6 only; the note picks the noise rate (note mod 32, " +
                "so C-0..G-2 are rates 0-31 and the pattern repeats every 32 semitones). Sample: a recording streamed through " +
                "the channel's D/A (DDA), on any channel.",
            "Samples play one 5-bit frame per interrupt of the CPU timer: the song's DDA rate, 6991 Hz / (T + 1) (Song view, " +
                "Txx). A sample sound's root note plays it at that rate; higher notes skip frames, lower ones repeat them.",
            "Up to four effect columns per channel, run in column order. Volume (Cxx, envelope), balance (8xy) and wave act " +
                "from their row on; Pxx detunes until the next note.",
            "The chip has one master balance (Xxy), one LFO and one timer for all six channels, set in the Song view and by " +
                "effects that act on every channel at once. The LFO: PSG 2's wave, stepping Lxx times slower than PSG 2's " +
                "note, bends PSG 1's pitch at the depth Mxx sets (01-03; +80 halts it). PSG 2 keeps sounding while it is the " +
                "LFO: C00 on PSG 2 leaves only the vibrato on PSG 1.",
            "Volume and balance multiply: channel volume (1.5 dB steps), channel balance and master balance (3 dB steps per " +
                "side) add up their attenuation.",
            "No chords: each channel is one voice, so spread a chord over channels.",
        )
        override val tonalChannels = "all six PSG channels"
        override val tonalChannelsShort = "PSG"
        override val untonalNote = "Every channel is tonal; on a noise sound the note picks the noise rate, on a sample its playback speed."
        override val sketchChannels = "PSG 1-6"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            PceInstrumentView.helpSections(keys) + PceWaveView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = PceValidator.limits(song)
    }

    /** The editor's longer reading of an arpeggio (`0xy`): the notes it steps through from the row's (or the sounding) note. */
    private object EffectText {
        fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
            val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
            val cell = track.getOrNull(row) ?: return null
            val code = cell.effectCode(column)
            val param = cell.effectParam(column)
            if (code != PceRules.FX_ARPEGGIO || param == 0) return null
            val x = param shr 4
            val y = param and 0x0F
            val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
            return listOf(root, root + x, root + y).joinToString(" → ") { name(it) } + " every tick"
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

        private fun name(note: Int): String = if (note in PceRules.NOTES) noteName(note) else "(out of range)"
    }
}

/**
 * The Song view's PC Engine rows: multispeed and the chip's shared state at the song start — master balance (left,
 * right), the LFO (frequency, control) and the DDA rate (the CPU timer). Effects (`X L M T`) change them while the
 * song plays.
 */
object PceSongSettings {
    private fun with(song: Song, f: (PceSound) -> PceSound): Song = f(song.pce).let { if (it == song.pce) song else song.copy(sound = it) }

    /** Register 9's values in stepping order: off, the three depths, then the same halted. */
    private val LFO_CONTROLS = listOf(0x00, 0x01, 0x02, 0x03, 0x80, 0x81, 0x82, 0x83)

    val ALL: List<SongSetting> = listOf(
        SongSetting("Multispeed", "Player ticks per frame", "Set multispeed",
            value = { s ->
                val m = s.pce.multispeed
                if (m == 1) "1×   60 ticks per second" else "$m×   ${60 * m} ticks per second: speeds and effects count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + 4 * c).coerceIn(1, PceSound.MAX_MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ),
        SongSetting("Master left", "Master balance, left", "Set master left",
            value = { s -> "${s.pce.masterLeft} of 15   left output for every channel, 3 dB per step (Xxy)" },
            adjust = { s, f, c -> with(s) { it.copy(master = ((it.masterLeft + f + 4 * c).coerceIn(0, 15) shl 4) or it.masterRight) } },
            reset = { s -> with(s) { it.copy(master = 0xF0 or it.masterRight) } },
        ),
        SongSetting("Master right", "Master balance, right", "Set master right",
            value = { s -> "${s.pce.masterRight} of 15   right output for every channel, 3 dB per step (Xxy)" },
            adjust = { s, f, c -> with(s) { it.copy(master = (it.masterLeft shl 4) or (it.masterRight + f + 4 * c).coerceIn(0, 15)) } },
            reset = { s -> with(s) { it.copy(master = (it.masterLeft shl 4) or 0x0F) } },
        ),
        SongSetting("LFO frequency", "Shared LFO frequency", "Set LFO frequency",
            value = { s ->
                val f = s.pce.lfoFrequency
                "${NumberText.hex(f, 2)}   PSG 2's wave steps ${if (f == 0) 256 else f}× slower than its note " +
                    "(${PceRules.hzText(PceRules.lfoHz(57, f))} at A-4; Lxx)"
            },
            adjust = { s, f, c -> with(s) { it.copy(lfoFrequency = (it.lfoFrequency + f + 16 * c).coerceIn(0, 255)) } },
            reset = { s -> with(s) { it.copy(lfoFrequency = 0) } },
        ),
        SongSetting("LFO control", "Shared LFO depth and halt", "Set LFO control",
            value = { s ->
                val v = s.pce.lfoControl
                "${NumberText.hex(v, 2)}   ${PceRules.lfoControlText(v)}" +
                    if (v and 3 == 0 || v and PceRules.LFO_HALT != 0) "; PSG 1 plays plain (Mxx)" else ": PSG 2's wave bends PSG 1's pitch (Mxx)"
            },
            adjust = { s, f, c ->
                if (f + c == 0) s else with(s) {
                    val i = LFO_CONTROLS.indexOf(it.lfoControl).coerceAtLeast(0)
                    it.copy(lfoControl = LFO_CONTROLS[(i + f + 4 * c).mod(LFO_CONTROLS.size)])
                }
            },
            reset = { s -> with(s) { it.copy(lfoControl = 0) } },
        ),
        SongSetting("DDA rate", "Sample rate (CPU timer)", "Set DDA rate",
            value = { s ->
                val t = s.pce.timerReload
                "${NumberText.hex(t, 2)}   ${PceRules.ddaText(t)}: one sample frame per timer interrupt at the root note (Txx)"
            },
            adjust = { s, f, c -> with(s) { it.copy(timerReload = (it.timerReload + f + 16 * c).coerceIn(0, PceSound.MAX_TIMER)) } },
            reset = { s -> with(s) { it.copy(timerReload = 0) } },
        ),
    )
}

/**
 * The HuC6280's shared state at a song position, as straight play reaches it: the song's start values, then every
 * shared effect (`X L M T`) in the order the player runs them — orders that play (a parked order only on its own),
 * rows, then within a row channels 0..5 at tick 0 and delayed rows (`Gxx`) at their tick, effect columns in order, so
 * the last one wins; a delay the row's speed never reaches does not run. Jumps and breaks are not followed (the walk is
 * sequential, as the validator's). Out-of-range parameters (rejected by the loader) are skipped.
 */
object PceSharedState {
    /** The shared values: master balance (register 1), LFO frequency and control (8, 9), DDA timer reload. */
    data class Shared(val master: Int, val lfoFrequency: Int, val lfoControl: Int, val timer: Int) {
        /** True when PSG 2's wave bends PSG 1's pitch: a depth set and not halted. */
        val lfoActive: Boolean get() = PceRules.lfoBends(lfoControl)
    }

    private const val CHANNELS = PcePlayerImage.CHANNELS

    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /** The song start: the header's values. */
    fun start(sound: PceSound) = Shared(sound.master, sound.lfoFrequency, sound.lfoControl, sound.timerReload)

    /** The shared state after row [row] of order [order] has run (the song start when [order] does not exist). */
    fun at(song: Song, order: Int, row: Int): Shared {
        var s = start(song.pce)
        if (order !in song.orders.indices) return s
        var speed = song.ticksPerRow
        val delays = IntArray(CHANNELS)
        fun run(c: Cell) {
            for (col in 0..c.more.size) {
                val param = c.effectParam(col)
                when (c.effectCode(col)) {
                    PceRules.FX_MASTER -> s = s.copy(master = param)
                    PceRules.FX_LFO_FREQ -> s = s.copy(lfoFrequency = param)
                    PceRules.FX_LFO_CTRL -> if (PceRules.lfoControlValid(param)) s = s.copy(lfoControl = param)
                    PceRules.FX_TIMER -> if (param <= PceSound.MAX_TIMER) s = s.copy(timer = param)
                }
            }
        }
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns.getOrNull(song.orders[o]) ?: continue
            if (p.tracks.size != CHANNELS) continue
            val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
            for (r in 0..last) {
                for (ch in 0 until CHANNELS) {
                    val c = p.tracks[ch][r]
                    for (col in 0..c.more.size) if (c.effectCode(col) == PceRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                var delayed = false
                for (ch in 0 until CHANNELS) {
                    val c = p.tracks[ch][r]
                    var d = 0
                    for (col in 0..c.more.size) if (c.effectCode(col) == PceRules.FX_DELAY && c.effectParam(col) > 0) d = c.effectParam(col)
                    delays[ch] = d
                    if (d == 0) run(c) else delayed = true
                }
                if (delayed) for (tick in 1 until speed) for (ch in 0 until CHANNELS) if (delays[ch] == tick) run(p.tracks[ch][r])
            }
        }
        return s
    }

    /**
     * [at] as the Pattern view shows it: "lfo" on PSG 2 and "mod" on PSG 1 while the LFO bends PSG 1, and three
     * inspector lines (master balance, LFO, DDA rate). Cached per position.
     */
    fun state(song: Song, order: Int, row: Int): SharedState {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val s = at(song, order, row)
        val tags = List(CHANNELS) {
            when {
                !s.lfoActive -> null
                it == PceRules.LFO_SOURCE -> "lfo"
                it == PceRules.LFO_TARGET -> "mod"
                else -> null
            }
        }
        val f = if (s.lfoFrequency == 0) 256 else s.lfoFrequency
        val lines = listOf(
            "Master ${PceRules.balanceText(s.master)}",
            if (s.lfoControl == 0) "LFO off" else "LFO ${PceRules.lfoControlText(s.lfoControl).lowercase()} · PSG 2 ÷ $f",
            "DDA ${PceRules.ddaText(s.timer)} (timer ${NumberText.hex(s.timer, 2)})",
        )
        val state = SharedState(tags, lines)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }
}
