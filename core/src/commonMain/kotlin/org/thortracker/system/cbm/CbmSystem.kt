package org.thortracker.system.cbm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.CbmImage
import org.thortracker.backend.CbmLowering
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
import kotlin.math.ceil

/** The VIC-20's 6560/6561 sound, played by Thor Tracker's own player ([CbmSystem]). */
object VicSystem : CbmSystem(CbmChip.VIC)

/** The C16/Plus/4's 7360/8360 TED sound, played by Thor Tracker's own player ([CbmSystem]). */
object TedSystem : CbmSystem(CbmChip.TED)

/**
 * The two Commodore systems Thor Tracker's own player drives (docs/AUDIO_ENGINE.md, "VIC-20 and C16/Plus/4"), one
 * implementation for both: [chip] picks the lanes, ranges, effects and sound kinds. Each lane is one hardware voice
 * (no chords, no voice allocation); the chip's one master volume and the TED's DAC timer are song data ([CbmSound])
 * changed by shared effects, and a sound's volume envelope takes the master volume while it sounds. Two effect
 * columns per lane, one bank of [SLOTS] sounds. Exports: the project and WAV only (VGM defines no VIC or TED chip).
 */
abstract class CbmSystem internal constructor(val chip: CbmChip) : SoundSystem {
    override val id: String = if (chip == CbmChip.VIC) CbmImage.VIC_ENGINE_ID else CbmImage.TED_ENGINE_ID
    override val name: String = if (chip == CbmChip.VIC) "VIC-20 (VIC)" else "C16 / Plus/4 (TED)"

    override val channels: List<ChannelSpec> = CbmRules.laneNames(chip).mapIndexed { ch, lane ->
        when {
            CbmRules.isVicNoise(chip, ch) -> ChannelSpec(lane, ChannelKind.NOISE, 0, tonal = false)
            CbmRules.isDac(chip, ch) -> ChannelSpec(lane, ChannelKind.WAVE, 0, tonal = false)
            else -> ChannelSpec(lane, ChannelKind.PULSE, 0, tonal = true)
        }
    }
    override val banks = listOf(BankSpec("Sounds", SLOTS))

    /**
     * Every note any lane takes, 0..127; each lane plays its own part of them ([laneRange], by chip and machine), checked
     * per cell and per audition (a key outside the Listen lane's range explains it).
     */
    override val noteRange: IntRange = CbmRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    /** Scientific names; notes 120-127 (VIC noise rates, TED voice 2 noise) write octave 10 as "A" to keep three characters. */
    override fun noteName(note: Int): String =
        if (note in 120..CbmImage.NOISE_RATE_MAX) NAMES[note % 12] + "A" else Songs.noteName(note, nameOctaveOffset)

    override val effects: List<EffectSpec> get() = CbmRules.effects(chip)

    override val views = ViewKind.entries.toSet() - ViewKind.WAVE

    /** The project and WAV (rendered by the generic native WAV export); no register-log format has these chips. */
    override val exports: List<ExportFormat> = emptyList()
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help()

    /** OFF is a note-column event (release or stop). */
    override val offEffect: String? = null

    /** One tick per PAL frame at multispeed 1 (the song's machine and multispeed: [ticksPerSecond]). */
    override val tickHz: Double = CbmRules.frameHz(chip, CbmVideo.PAL)
    override val maxOrders = CbmImage.MAX_ORDERS

    /** The machine's exact frame rate times the multispeed. */
    override fun ticksPerSecond(song: Song): Double {
        val sound = song.sound as? CbmSound ?: return tickHz
        return CbmRules.frameHz(chip, sound.video) * sound.multispeed
    }

    /** The notes [channel] plays in [song]'s machine. */
    fun laneRange(song: Song, channel: Int): IntRange = CbmRules.laneRange(chip, song.cbm.video, channel)

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = CbmSound.blank(chip),
    )

    override val soundSerializer: KSerializer<CbmSound> = CbmSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = if (chip == CbmChip.VIC) CbmDemoSong.vic() else CbmDemoSong.ted()

    override fun effectsFor(channel: Int): List<EffectSpec> = CbmRules.effectsFor(chip, channel)

    override fun effectColumns(channel: Int): Int = CbmRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = CbmRules.describe(chip, song, channel, effect, param)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = CbmValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = CbmValidator.structuralErrors(this, song)
    override fun errors(song: Song) = CbmValidator.errors(this, song)
    override fun warnings(song: Song) = CbmValidator.warnings(this, song)

    /**
     * Parked orders removed; the loop order and every `Bxx` renumbered to the played orders. A target that is parked
     * lands on the next played order after it (the loop order past the last).
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

    /** The order at [order] alone, looping: every `Bxx` in it aims at that one order. */
    override fun patternLoop(song: Song, order: Int): Song {
        val index = song.orders[order.coerceIn(0, song.orders.size - 1)]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        val patterns = if (tracks == pattern.tracks) song.patterns else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList())
    }

    /** `Bxx` jumps to order xx; the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == CbmRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == CbmRules.FX_JUMP) out = out.withEffect(c, CbmRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine()

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.cbm.instrument(slot)?.name?.ifEmpty { "Sound ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.cbm.instrument(slot)?.let { summary(song.cbm, it) } ?: "Empty: choose a preset"

    /** A square sound's shape (half high, half low); none for noise and samples. */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0) return null
        val ins = song.cbm.instrument(slot)?.takeIf { it.kind == CbmKind.TONE && it.wave == CbmImage.WAVE_SQUARE } ?: return null
        return if (ins.waves.values.firstOrNull() == CbmImage.WAVE_NOISE) null else SQUARE_SHAPE
    }

    /** One line about [ins]: "Square · master volume, arpeggio envelopes", "Sample 00 Kick · 1200 frames · root C-4 · one-shot". */
    fun summary(sound: CbmSound, ins: CbmInstrument): String {
        val envelopes = buildList {
            if (ins.kind == CbmKind.TONE && !ins.volume.isEmpty) add("master volume")
            if (!ins.arpeggio.isEmpty) add("arpeggio")
            if (!ins.pitch.isEmpty) add(if (ins.pitchRelative) "relative pitch" else "pitch")
            if (ins.kind == CbmKind.TONE && !ins.waves.isEmpty) add("wave")
        }
        val head = when {
            ins.kind == CbmKind.SAMPLE -> {
                val s = sound.samples.getOrNull(ins.sample)
                "Sample ${NumberText.hex(ins.sample, 2)}" + (s?.let { " ${it.name} · ${it.data.size} frames" } ?: " (missing)") +
                    " · root ${noteName(ins.root)} · " + (if (ins.loop) "loop from ${ins.loopStart}" else "one-shot")
            }
            chip == CbmChip.VIC -> "Square (noise on the Noise lane)"
            ins.playsNoise && ins.wave == CbmImage.WAVE_NOISE -> "Noise (Voice 2)"
            ins.playsNoise -> "Square and noise (Voice 2)"
            else -> "Square"
        }
        return if (envelopes.isEmpty()) head else "$head · ${envelopes.joinToString(", ")} envelope${if (envelopes.size > 1) "s" else ""}"
    }

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) CbmInstrumentView else null

    /**
     * A one-shot DAC note ends by itself: the rows its frames last at the note's step and the song's DAC rate (header
     * timer; `Txx` changes are not followed), over the rows' speeds from the song's ticks per row and `Fxx` on any lane
     * of the pattern. Null for other notes, looping samples or a note that outlasts the pattern.
     */
    override fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? {
        if (!CbmRules.isDac(chip, channel)) return null
        val p = song.patterns.getOrNull(pattern) ?: return null
        val track = p.tracks.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row)?.takeIf { it.note >= 0 } ?: return null
        val slot = if (cell.instrument > 0) cell.instrument else (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } } ?: return null
        val sound = song.cbm
        val ins = sound.instrument(slot)?.takeIf { it.kind == CbmKind.SAMPLE && !it.loop } ?: return null
        val frames = sound.samples.getOrNull(ins.sample)?.data?.size ?: return null
        val ticks = CbmSharedState.oneShotTicks(sound, frames, cell.note, ins.root)
        fun speedAfter(r: Int, speed: Int): Int {
            var s = speed
            for (t in p.tracks) {
                val c = t[r]
                for (col in 0..c.more.size) if (c.effectCode(col) == CbmRules.FX_SPEED && c.effectParam(col) > 0) s = c.effectParam(col)
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

    override val songSettings: List<SongSetting> by lazy { CbmSongSettings.forChip(chip) }

    /** The master volume, its owner, and on the TED the DAC rate and D/A mode where straight play reaches. */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState = CbmSharedState.state(song, order, row)

    private inner class Engine : PlaybackEngine {
        override val id = this@CbmSystem.id
        override val driver = "Thor Tracker's own player on " +
            (if (chip == CbmChip.VIC) "VICE's VIC-20 sound (6560/6561, viznut's model)" else "plus4emu's TED sound (7360/8360)") + ", blip_buf 1.1.0"
        override val imageSize = CbmImage.IMAGE_SIZE

        /** 72 s of a PAL song at multispeed 1. */
        override val seekTicks = CbmImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = CbmLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val sound = song.cbm
            val ins = if (instrument in 1..SLOTS) sound.instrument(instrument) else null
            val range = CbmRules.laneRange(chip, sound.video, channel)
            val lane = channels.getOrNull(channel)?.name
            return when {
                instrument !in 1..SLOTS -> "Choose a sound (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Sound ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices -> "Cannot audition on channel $channel"
                note !in range -> "${noteName(note)} is outside $lane's ${noteName(range.first)}..${noteName(range.last)}" +
                    if (chip == CbmChip.VIC && !CbmRules.isVicNoise(chip, channel)) " on a ${sound.video.name} VIC-20" else ""
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> CbmRules.placementProblem(chip, channel, ins)?.let { "\"${ins.name}\" $it" }
                    ?: CbmValidator.instrumentProblem(sound, instrument)?.let { "Sound ${NumberText.hex(instrument, 2)} \"${ins.name}\" $it" }
            }
        }

        /** The player takes absolute notes (VIC noise: rate indices). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** 1/64 semitone, the player's unit; the VIC noise lane has no fine pitch. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = if (CbmRules.isVicNoise(chip, channel)) 0 else fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in a free effect column: the
     * player requests every degree within 0.8 cents; the chip's period then rounds it (the VIC's 7-bit periods coarsely).
     */
    private object Fine : FinePitch {
        override val name = "Pxx"
        override val max = 63
        override fun range(note: Int): IntRange = 0..max
        override fun cents(note: Int, fine: Int): Double = fine * 100.0 / 64

        override fun of(cell: Cell): Int? {
            val col = column(cell) ?: return 0
            val p = cell.effectParam(col)
            return if (p >= CbmRules.FINE_CENTRE) p - CbmRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= CbmRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < CbmRules.FINE_CENTRE) return null
            val col = current ?: (0 until CbmRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, CbmRules.FX_FINE, CbmRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == CbmRules.FX_FINE }
    }

    private inner class Help : SystemHelp {
        override val off = "Release: sounds whose master volume or wave envelope has a release point play on from it; other sounds stop at once"
        override val offEntered = "OFF: release (or stop without a release point)"
        override val offInspector = "OFF: release, or stop without a volume or wave release point"
        override val effectsIntro: List<String> = if (chip == CbmChip.VIC) listOf(
            "Played by Thor Tracker's own player on VICE's model of the VIC-20's 6560/6561: one tick per video frame " +
                "(50.04 Hz PAL, 60.28 Hz NTSC; Song view) times the multispeed.",
            "Three square voices an octave apart (Bass, Alto, Soprano: each plays its own three and a bit octaves, coarsely " +
                "tuned: the chip's 7-bit periods are its character) and a Noise voice whose note picks the noise rate (higher " +
                "is brighter).",
            "The chip has ONE master volume for all four voices: Vxx sets it, Axy slides it, and a sound with a volume " +
                "envelope takes it while its note sounds, shaping every voice (the lane tag VOL shows which). For short notes " +
                "on one voice use a wave envelope (0 gates the voice off).",
            "Two effect columns per lane, run in column order. Pxx detunes until the next note; the Noise lane ignores " +
                "slides, portamento, vibrato and Pxx.",
            "No chords: each lane is one voice, so spread a chord over lanes.",
        ) else listOf(
            "Played by Thor Tracker's own player on plus4emu's model of the C16/Plus/4's TED: one tick per video frame " +
                "(49.86 Hz PAL, 59.92 Hz NTSC; Song view) times the multispeed.",
            "Two square voices; Voice 2 can play noise instead (a sound's wave 02, a wave envelope, or W02). The chip has " +
                "ONE volume 0-8 for both: Vxx sets it, Axy slides it, and a sound with a volume envelope takes it while its " +
                "note sounds (the lane tag VOL shows which).",
            "The DAC lane plays samples through the TED's D/A mode: while a DAC note sounds it takes both voices (they are " +
                "silent) and the volume register becomes a 9-level DAC. One frame per timer interrupt at the root note: the " +
                "song's DAC rate (Song view, Txx).",
            "Two effect columns per lane, run in column order. Wxx switches a voice's wave (00 off, 01 square, 02 noise on " +
                "Voice 2); Pxx detunes until the next note.",
            "No chords: each lane is one voice, so spread a chord over lanes.",
        )
        override val tonalChannels = if (chip == CbmChip.VIC) "Bass, Alto and Soprano" else "Voice 1 and Voice 2"
        override val tonalChannelsShort = if (chip == CbmChip.VIC) "Bass/Alto/Soprano" else "Voices"
        override val untonalNote = if (chip == CbmChip.VIC) "Noise keeps every rate." else "The DAC lane keeps every note (sample pitches)."
        override val sketchChannels = if (chip == CbmChip.VIC) "Bass, Alto or Soprano; Noise has no tonal chord reference"
            else "Voice 1 or Voice 2; the DAC lane plays samples"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = CbmInstrumentView.helpSections(keys, chip)

        override fun limits(song: Song): List<Pair<String, String>> = CbmValidator.limits(song)
    }

    companion object {
        const val SLOTS = CbmImage.MAX_INSTRUMENTS

        /** What [instrumentName] shows for an empty slot. */
        const val EMPTY_NAME = "(empty)"

        private val NAMES = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-")
        private val SQUARE_SHAPE = List(32) { if (it < 16) 1f else 0f }

        /** The system of [song]'s chip. */
        fun of(song: Song): CbmSystem = song.cbm.chip.system
    }
}

/** The Song view's VIC/TED rows: the machine, multispeed, the master volume at the start and (TED) the DAC rate. */
object CbmSongSettings {
    private fun with(song: Song, f: (CbmSound) -> CbmSound): Song = f(song.cbm).let { if (it == song.cbm) song else song.copy(sound = it) }

    fun forChip(chip: CbmChip): List<SongSetting> = buildList {
        add(SongSetting("Machine", "PAL or NTSC", "Set machine",
            value = { s ->
                val video = s.cbm.video
                val hz = NumberText.fixed(CbmRules.frameHz(chip, video), 2)
                val part = when (chip) {
                    CbmChip.VIC -> if (video == CbmVideo.PAL) "6561" else "6560"
                    CbmChip.TED -> if (video == CbmVideo.PAL) "8360" else "7360"
                }
                "${video.name}   $part, $hz frames per second; the chip clock tunes every note"
            },
            adjust = { s, f, c -> if ((f + c) % 2 == 0) s else with(s) { it.copy(video = if (it.video == CbmVideo.PAL) CbmVideo.NTSC else CbmVideo.PAL) } },
            reset = { s -> with(s) { it.copy(video = CbmVideo.PAL) } },
        ))
        add(SongSetting("Multispeed", "Player ticks per frame", "Set multispeed",
            value = { s ->
                val m = s.cbm.multispeed
                val tps = NumberText.fixed(CbmRules.frameHz(chip, s.cbm.video) * m, 1)
                if (m == 1) "1×   $tps ticks per second" else "$m×   $tps ticks per second: speeds and effects count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + c).coerceIn(1, CbmSound.MAX_MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ))
        add(SongSetting("Master volume", "The chip's one volume at the start", "Set master volume",
            value = { s -> "${s.cbm.volume} of ${chip.maxVolume}   every voice at the song start (Vxx, Axy; volume envelopes shape it)" },
            adjust = { s, f, c -> with(s) { it.copy(volume = (it.volume + f + 4 * c).coerceIn(0, chip.maxVolume)) } },
            reset = { s -> with(s) { it.copy(volume = chip.maxVolume) } },
        ))
        if (chip == CbmChip.TED) add(SongSetting("DAC rate", "Sample rate (TED timer)", "Set DAC rate",
            value = { s ->
                val t = s.cbm.dacTimer
                "${NumberText.hex(t, 2)}   ${CbmRules.dacText(s.cbm.video, t)}: one sample frame per timer interrupt at the root note (Txx)"
            },
            adjust = { s, f, c -> with(s) { it.copy(dacTimer = (it.dacTimer + f + 16 * c).coerceIn(0, CbmSound.MAX_TIMER)) } },
            reset = { s -> with(s) { it.copy(dacTimer = CbmSound.DEFAULT_TIMER) } },
        ))
    }
}

/**
 * The chip's shared state at a song position as straight play reaches it (docs/AUDIO_ENGINE.md, "Lanes and shared
 * resources"): the master volume level M (header, `Vxx`, `Axy` slides tick by tick), the lane that owns the master
 * volume envelope (a key-on of a tone sound with a volume envelope takes it; a key-on without one on the owner lane or
 * the owner's note stopping ends it), and on the TED the DAC timer (`Txx`) and whether D/A mode holds both voices (a
 * DAC note sounding: until OFF, or a one-shot's last frame at the timer rate). Orders that play before the position are
 * walked in order (a parked order only on its own), rows by their ticks: delayed rows (`Gxx`) at their tick, `Sxx` at
 * theirs, effect columns in order, jumps not followed. The state is the one the position's tick 0 plays with.
 */
object CbmSharedState {
    /** [level] = M, [owner] the owning lane (-1 none) and its sound [ownerSlot], [dacActive], [timer] = T. */
    data class Shared(val level: Int, val owner: Int, val ownerSlot: Int, val dacActive: Boolean, val timer: Int)

    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /**
     * Ticks a one-shot sample of [frames] frames lasts on [note] with root [root] at [sound]'s header DAC rate: the
     * player stops at the first interrupt k whose position 65536 + k · step reaches the end, k = ⌈(frames − 1) · 65536 /
     * step⌉ interrupts of 64 + 4 T clocks, at F / N clocks per tick.
     */
    fun oneShotTicks(sound: CbmSound, frames: Int, note: Int, root: Int): Int {
        val step = CbmRules.sampleStep(note * 64, root)
        val k = ((frames - 1).toLong() * 65536 + step - 1) / step
        val cycles = k * CbmRules.timerCycles(sound.dacTimer)
        val frame = CbmRules.frameCycles(sound.chip, sound.video).toLong()
        return ceil(cycles.toDouble() * sound.multispeed / frame).toInt()
    }

    /** The shared state as the tick 0 of row [row] of order [order] plays it (the song start when [order] does not exist). */
    fun at(song: Song, order: Int, row: Int): Shared = Walk(song).run(order, row)

    private class Walk(val song: Song) {
        val sound = song.cbm
        val chip = sound.chip
        val lanes = chip.channels
        val max8 = chip.maxVolume * 8
        var m8 = sound.volume * 8
        var slide = 0
        var owner = -1
        var timer = sound.dacTimer
        var dacActive = false
        var dacEnd = Long.MAX_VALUE
        var tick = 0L
        val slot = IntArray(lanes)
        val sounding = BooleanArray(lanes)
        val portamento = BooleanArray(lanes)
        val cut = IntArray(lanes) { -1 }
        var speed = song.ticksPerRow

        fun state() = Shared(m8 shr 3, owner, if (owner >= 0) slot[owner] else 0, dacActive, timer)

        fun run(order: Int, row: Int): Shared {
            if (order !in song.orders.indices) return state()
            val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
            for (o in before + order) {
                val p = song.patterns.getOrNull(song.orders[o]) ?: continue
                if (p.tracks.size != lanes) continue
                val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
                for (r in 0..last) {
                    // Fxx acts from its own row on; the last one of the row wins.
                    for (ch in 0 until lanes) {
                        val c = p.tracks[ch][r]
                        for (col in 0..c.more.size) if (c.effectCode(col) == CbmRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                    }
                    val delays = IntArray(lanes) { ch ->
                        val c = p.tracks[ch][r]
                        var d = 0
                        for (col in 0..c.more.size) if (c.effectCode(col) == CbmRules.FX_DELAY && c.effectParam(col) > 0) d = c.effectParam(col)
                        d
                    }
                    for (ch in 0 until lanes) cut[ch] = -1
                    for (t in 0 until speed) {
                        for (ch in 0 until lanes) if (delays[ch] == t) runCell(ch, p.tracks[ch][r])
                        for (ch in 0 until lanes) if (cut[ch] == t) off(ch)
                        if (dacActive && tick >= dacEnd) { stopDac() }
                        if (o == order && r == last && t == 0) return state()
                        if (slide != 0) m8 = (m8 + slide).coerceIn(0, max8)
                        tick++
                    }
                }
            }
            return state()
        }

        fun runCell(ch: Int, c: Cell) {
            if (c.instrument > 0) slot[ch] = c.instrument
            for (col in 0..c.more.size) {
                val code = c.effectCode(col)
                val param = c.effectParam(col)
                when (code) {
                    CbmRules.FX_VOLUME -> if (param <= chip.maxVolume) m8 = param * 8
                    CbmRules.FX_VOLUME_SLIDE -> slide = (param shr 4) - (param and 15)
                    CbmRules.FX_TIMER -> if (chip == CbmChip.TED) timer = param
                    CbmRules.FX_PORTAMENTO -> portamento[ch] = param > 0
                    CbmRules.FX_CUT -> cut[ch] = param
                }
            }
            when {
                c.note == Cell.CUT -> off(ch)
                c.note >= 0 -> if (!(portamento[ch] && sounding[ch] && !CbmRules.isVicNoise(chip, ch))) keyOn(ch, c.note)
            }
        }

        fun keyOn(ch: Int, note: Int) {
            val ins = sound.instrument(slot[ch]) ?: return
            if (CbmRules.placementProblem(chip, ch, ins) != null) return
            sounding[ch] = true
            if (ins.kind == CbmKind.TONE && !ins.volume.isEmpty) owner = ch
            else if (owner == ch) owner = -1
            if (CbmRules.isDac(chip, ch)) {
                dacActive = true
                val frames = sound.samples.getOrNull(ins.sample)?.data?.size
                dacEnd = if (ins.loop || frames == null) Long.MAX_VALUE else tick + oneShotTicks(sound.copy(dacTimer = timer), frames, note, ins.root)
            }
        }

        fun off(ch: Int) {
            if (!sounding[ch]) return
            val ins = sound.instrument(slot[ch])
            if (ins != null && ins.kind == CbmKind.TONE && ins.releases) return
            sounding[ch] = false
            if (owner == ch) owner = -1
            if (CbmRules.isDac(chip, ch)) { dacActive = false; dacEnd = Long.MAX_VALUE }
        }

        fun stopDac() {
            val ch = CbmImage.TED_DAC_CHANNEL
            sounding[ch] = false
            dacActive = false
            dacEnd = Long.MAX_VALUE
        }
    }

    /**
     * [at] as the Pattern view shows it: "VOL" on the lane owning the master volume, on the TED "DAC" on the DAC lane
     * and "D/A" on the voices while D/A mode holds them; inspector lines for the volume and (TED) the DAC rate.
     */
    fun state(song: Song, order: Int, row: Int): SharedState {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val sound = song.cbm
        val chip = sound.chip
        val s = at(song, order, row)
        val names = CbmRules.laneNames(chip)
        val tags = List(chip.channels) { ch ->
            when {
                ch == s.owner -> "VOL"
                s.dacActive && CbmRules.isDac(chip, ch) -> "DAC"
                s.dacActive -> "D/A"
                else -> null
            }
        }
        val ownerText = if (s.owner < 0) "no volume envelope owns it" else {
            val ins = sound.instrument(s.ownerSlot)
            "${names[s.owner]}'s sound ${NumberText.hex(s.ownerSlot, 2)}${ins?.let { " ${it.name}" } ?: ""} shapes it"
        }
        val lines = buildList {
            add("Master volume ${s.level} of ${chip.maxVolume} · $ownerText")
            if (chip == CbmChip.TED) add("DAC ${CbmRules.dacText(sound.video, s.timer)} (timer ${NumberText.hex(s.timer, 2)})" +
                if (s.dacActive) " · D/A mode: the DAC holds both voices" else "")
        }
        val state = SharedState(tags, lines)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }
}
