package org.thortracker.system.opm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.OpmLowering
import org.thortracker.backend.OpmPlayerImage
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
 * The Yamaha YM2151 (OPM) at the Sharp X68000's 4 MHz, played by Thor Tracker's own player on ymfm (native
 * `"ym2151-opm"`; music-only, exported as VGM): eight 4-operator FM channels, up to four effect columns per channel,
 * one bank of [SLOTS] instruments with operator registers, curated controls ([OpmCurated]) and a drum pitch sweep. The
 * chip's one LFO, noise generator (FM 8 only) and CSM timer are song data ([OpmSound]) changed by shared effects; see
 * docs/AUDIO_ENGINE.md ("OPM").
 */
object OpmSystem : SoundSystem {
    override val id = OpmPlayerImage.ENGINE_ID
    override val name = "OPM FM (YM2151)"

    const val SLOTS = OpmPlayerImage.MAX_INSTRUMENTS
    const val MAX_MULTISPEED = OpmPlayerImage.MAX_MULTISPEED

    override val channels = OpmRules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.VOICE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = OpmRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = OpmRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(ExportFormat("vgm") { OpmVgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is a note-column event (key off). */
    override val offEffect: String? = null

    /** One player tick per 60 Hz frame at multispeed 1. */
    override val tickHz = 60.0
    override val maxOrders = OpmPlayerImage.MAX_ORDERS

    override fun ticksPerSecond(song: Song): Double = tickHz * song.opm.multispeed

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = OpmSound.blank(),
    )

    override val soundSerializer: KSerializer<OpmSound> = OpmSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = OpmDemoSong.build()

    /** Every effect on every channel, except `Nxx` (the noise generator's frequency): FM 8 only. */
    override fun effectsFor(channel: Int): List<EffectSpec> = OpmRules.effectsFor(channel)

    override fun effectColumns(channel: Int): Int = OpmRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = OpmRules.describe(song, channel, effect, param)

    /** Arpeggios read with the notes they step through; locks on rows without a note say they are ignored. */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        EffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = OpmValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = OpmValidator.structuralErrors(song)
    override fun errors(song: Song) = OpmValidator.errors(song)
    override fun warnings(song: Song) = OpmValidator.warnings(song)

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
        for (c in 0..cell.more.size) if (cell.effectCode(c) == OpmRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == OpmRules.FX_JUMP) out = out.withEffect(c, OpmRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.let(OpmCurated::summary) ?: "Empty: choose a preset"

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** What [instrumentName] shows for an empty slot. */
    const val EMPTY_NAME = "(empty)"

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): OpmInstrument? = song.opm.instrument(slot)

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) OpmInstrumentView else null

    override val songSettings: List<SongSetting> get() = OpmSongSettings.ALL

    /** The chip's shared LFO, CSM and noise state where straight play reaches ([OpmSharedState]). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState = OpmSharedState.state(song, order, row)

    private object Engine : PlaybackEngine {
        override val id = OpmPlayerImage.ENGINE_ID
        override val driver = "Thor Tracker's OPM player on ymfm 81aec25c (YM2151 at 4 MHz, X68000), blip_buf 1.1.0"
        override val imageSize = OpmPlayerImage.IMAGE_SIZE

        /**
         * One minute of song at 60 ticks per second. Every tick of a seek clocks the chip as straight play does, so the
         * sound from the target is straight play's; further targets fall back to their order's start.
         */
        override val seekTicks = OpmPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = OpmLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) song.opm.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices -> "Cannot audition on channel $channel"
                note !in noteRange -> "${noteName(note)} is outside ${noteName(noteRange.first)}..${noteName(noteRange.last)}, the notes the 4 MHz YM2151 sounds"
                ins.noise && channel != OpmRules.NOISE_CHANNEL -> "\"${ins.name}\" plays the noise generator; audition it on FM 8"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> null
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column: the
     * player's pitch unit is 1/64 semitone (the chip's key fraction), so every degree is realised within 0.8 cents of
     * the request, plus the chip's own table (every note within 4.4 cents of 12-TET at 4 MHz).
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
            return if (p >= OpmRules.FINE_CENTRE) p - OpmRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= OpmRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < OpmRules.FINE_CENTRE) return null
            val col = current ?: (0 until OpmRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, OpmRules.FX_FINE, OpmRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == OpmRules.FX_FINE }
    }

    private object Help : SystemHelp {
        override val off = "Key off: the note fades with its instrument's release (while CSM is on, timer A restarts released notes)"
        override val offEntered = "OFF: key off (the release plays)"
        override val offInspector = "OFF: key off (release phase)"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player on a YM2151 (ymfm) at the X68000's 4 MHz: 60 ticks per second times the " +
                "song's multispeed.",
            "Up to four effect columns per channel, run in column order. Locks (E, I, M) shape the note started on their row " +
                "only; volume, pan, LFO sensitivity (H), slides and vibrato act from their row on.",
            "The chip has one LFO, one noise generator and one timer for all eight channels, set in the Song view and by " +
                "effects that act on every channel at once: L (LFO rate), W (waveform), T (amplitude depth) and V (pitch " +
                "depth); each instrument, or Hxy, chooses how much of the LFO it takes.",
            "CSM (X01 on, X00 off): at every overflow of timer A (rate Qxx) all eight channels' operators are keyed on for " +
                "one sample, so released notes restart their attack at that rate.",
            "Noise plays on FM 8 only: a noise instrument turns its operator 4 into the noise generator; Nxx sets its frequency.",
            "No chords: each channel is one chip voice, so spread a chord over channels. Drums are instruments with a pitch " +
                "sweep: the note picks the drum's pitch.",
        )
        override val tonalChannels = "all eight FM channels"
        override val tonalChannelsShort = "FM"
        override val untonalNote = "Every channel is tonal; drums sweep from their note (FM 8's noise keeps its own frequency)."
        override val sketchChannels = "FM 1-8"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = OpmInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = OpmValidator.limits(song)
    }

    /**
     * The editor's longer reading of an OPM effect where its row matters: the notes an arpeggio (`0xy`) steps through
     * from the row's (or the sounding) note, and why a lock (`E`, `I`, `M`) on a row without a note does nothing.
     */
    private object EffectText {
        fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
            val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
            val cell = track.getOrNull(row) ?: return null
            val code = cell.effectCode(column)
            val param = cell.effectParam(column)
            if (code in OpmRules.NOTE_EFFECTS && cell.note < 0) {
                return "${OpmRules.describe(song, channel, code, param)}: ignored, it applies when a note starts on its row"
            }
            if (code != OpmRules.FX_ARPEGGIO || param == 0) return null
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

        private fun name(note: Int): String = if (note in OpmRules.NOTES) noteName(note) else "(out of range)"
    }
}

/**
 * The Song view's OPM rows: multispeed and the chip's shared state at the song start — the LFO (rate, waveform,
 * amplitude and pitch depth) and CSM with its timer A rate. Effects (`L W T V X Q`) change them while the song plays.
 */
object OpmSongSettings {
    private fun with(song: Song, f: (OpmSound) -> OpmSound): Song = f(song.opm).let { if (it == song.opm) song else song.copy(sound = it) }

    private fun hz(value: Double) = OpmRules.hzText(value)

    val ALL: List<SongSetting> = listOf(
        SongSetting("Multispeed", "Player ticks per frame", "Set multispeed",
            value = { s ->
                val m = s.opm.multispeed
                if (m == 1) "1×   60 ticks per second" else "$m×   ${60 * m} ticks per second: speeds and effects count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + 4 * c).coerceIn(1, OpmSystem.MAX_MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ),
        SongSetting("LFO rate", "Shared LFO frequency", "Set LFO rate",
            value = { s ->
                val o = s.opm
                "${NumberText.hex(o.lfoRate, 2)}   ${hz(OpmRules.lfoHz(o.lfoRate))}" +
                    if (o.amd == 0 && o.pmd == 0) "; AMD and PMD are 0, so the LFO moves nothing yet" else " for every channel (Lxx)"
            },
            adjust = { s, f, c -> with(s) { it.copy(lfoRate = (it.lfoRate + f + 16 * c).coerceIn(0, 255)) } },
            reset = { s -> with(s) { it.copy(lfoRate = 0) } },
        ),
        SongSetting("LFO wave", "Shared LFO waveform", "Set LFO waveform",
            value = { s -> "${OpmRules.LFO_WAVE_NAMES[s.opm.lfoWave.coerceIn(0, 3)]}   the LFO's shape for every channel (Wxx)" },
            adjust = { s, f, c -> if (f + c == 0) s else with(s) { it.copy(lfoWave = (it.lfoWave + f + c).mod(OpmRules.LFO_WAVE_NAMES.size)) } },
            reset = { s -> with(s) { it.copy(lfoWave = 0) } },
        ),
        SongSetting("AMD", "LFO amplitude depth", "Set AMD",
            value = { s -> "${s.opm.amd} of 127   tremolo for instruments with AMS and AM operators (Txx)" },
            adjust = { s, f, c -> with(s) { it.copy(amd = (it.amd + f + 16 * c).coerceIn(0, 127)) } },
            reset = { s -> with(s) { it.copy(amd = 0) } },
        ),
        SongSetting("PMD", "LFO pitch depth", "Set PMD",
            value = { s -> "${s.opm.pmd} of 127   vibrato for instruments with PMS (Vxx)" },
            adjust = { s, f, c -> with(s) { it.copy(pmd = (it.pmd + f + 16 * c).coerceIn(0, 127)) } },
            reset = { s -> with(s) { it.copy(pmd = 0) } },
        ),
        SongSetting("CSM", "CSM mode", "Set CSM",
            value = { s ->
                if (s.opm.csm) "On   every channel's operators keyed at each timer A overflow, ${hz(OpmRules.csmHz(s.opm.timerA))} (X00 ends it)"
                else "Off   notes key on only when played (X01 turns CSM on)"
            },
            adjust = { s, f, c -> if ((f + c) % 2 == 0) s else with(s) { it.copy(csm = !it.csm) } },
            reset = { s -> with(s) { it.copy(csm = false) } },
        ),
        SongSetting("Timer A", "CSM rate (timer A)", "Set timer A",
            value = { s ->
                val o = s.opm
                "${o.timerA}   ${hz(OpmRules.csmHz(o.timerA))} CSM rate" + if (o.csm) " (Qxx sets its high 8 bits)" else ", heard while CSM is on"
            },
            adjust = { s, f, c -> with(s) { it.copy(timerA = (it.timerA + f + 64 * c).coerceIn(0, 1023)) } },
            reset = { s -> with(s) { it.copy(timerA = 0) } },
        ),
    )
}

/**
 * The YM2151's shared state at a song position, as straight play reaches it: the song's start values, then every
 * shared effect (`L W T V X Q`; `N` and FM 8's key-ons for the noise register) in the order the player runs them —
 * orders that play (a parked order only on its own), rows, then within a row channels 0..7 at tick 0 and delayed rows
 * (`Gxx`) at their tick, effect columns in order, so the last one wins; a delay the row's speed never reaches does not
 * run. FM 8 keys on (setting the noise from its instrument) unless the instrument slot is empty or portamento glides
 * to a note while it is still keyed. Jumps and breaks are not followed (the walk is sequential, as the validator's).
 */
object OpmSharedState {
    /** The shared registers' values: LFO rate/waveform/depths, CSM and timer A, and the noise register ($0F). */
    data class Shared(
        val lfoRate: Int,
        val lfoWave: Int,
        val amd: Int,
        val pmd: Int,
        val csm: Boolean,
        val timerA: Int,
        val noise: Int,
    ) {
        val noiseOn: Boolean get() = noise and 0x80 != 0
        val noiseFrequency: Int get() = noise and 0x1F
    }

    private const val CHANNELS = OpmPlayerImage.CHANNELS
    private const val NOISE = OpmRules.NOISE_CHANNEL

    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /** The song start: the header's values, noise off. */
    fun start(sound: OpmSound) = Shared(sound.lfoRate, sound.lfoWave, sound.amd, sound.pmd, sound.csm, sound.timerA, 0)

    /** The shared state after row [row] of order [order] has run (the song start when [order] does not exist). */
    fun at(song: Song, order: Int, row: Int): Shared {
        val sound = song.opm
        val walk = Walk(sound, song.ticksPerRow)
        if (order !in song.orders.indices) return walk.s
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns.getOrNull(song.orders[o]) ?: continue
            if (p.tracks.size != CHANNELS) continue
            val last = if (o == order) minOf(row, p.length - 1) else p.length - 1
            for (r in 0..last) walk.row(p.tracks, r)
        }
        return walk.s
    }

    /** [at] as the Pattern view shows it: a "noise" tag on FM 8 while noise is on, and three inspector lines. Cached per position. */
    fun state(song: Song, order: Int, row: Int): SharedState {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) cacheState?.let { return it }
        val s = at(song, order, row)
        val tags = List<String?>(CHANNELS) { if (it == NOISE && s.noiseOn) "noise" else null }
        val wave = OpmRules.LFO_WAVE_NAMES[s.lfoWave.coerceIn(0, 3)].lowercase()
        val lines = listOf(
            "LFO $wave ${OpmRules.hzText(OpmRules.lfoHz(s.lfoRate))} · AMD ${s.amd} · PMD ${s.pmd}",
            if (s.csm) "CSM on · ${OpmRules.hzText(OpmRules.csmHz(s.timerA))} (timer A ${s.timerA})" else "CSM off",
            if (s.noiseOn) "Noise on FM 8 · ${OpmRules.hzText(OpmRules.noiseHz(s.noiseFrequency))}" else "Noise off",
        )
        val state = SharedState(tags, lines)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }

    /** One straight-play walk: the shared values plus what FM 8's key-ons depend on. */
    private class Walk(private val sound: OpmSound, private var speed: Int) {
        var s = OpmSharedState.start(sound)
        private var instrument = 0
        private var keyed = false
        private var portamento = 0
        private val delays = IntArray(CHANNELS)

        fun row(tracks: List<List<Cell>>, r: Int) {
            for (ch in 0 until CHANNELS) {
                val c = tracks[ch][r]
                for (col in 0..c.more.size) if (c.effectCode(col) == OpmRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
            }
            var delayed = false
            for (ch in 0 until CHANNELS) {
                val c = tracks[ch][r]
                var d = 0
                for (col in 0..c.more.size) if (c.effectCode(col) == OpmRules.FX_DELAY && c.effectParam(col) > 0) d = c.effectParam(col)
                delays[ch] = d
                if (d == 0) run(c, ch, 0) else delayed = true
            }
            if (!delayed) return
            for (tick in 1 until speed) for (ch in 0 until CHANNELS) if (delays[ch] == tick) run(tracks[ch][r], ch, tick)
        }

        private fun run(c: Cell, ch: Int, tick: Int) {
            if (ch == NOISE && c.instrument != 0) instrument = c.instrument
            var lock = -1
            var cut = -1
            for (col in 0..c.more.size) {
                val code = c.effectCode(col)
                val param = c.effectParam(col)
                if (code == 0 && param == 0) continue
                when (code) {
                    OpmRules.FX_LFO_RATE -> s = s.copy(lfoRate = param)
                    OpmRules.FX_AMD -> if (param <= 0x7F) s = s.copy(amd = param)
                    OpmRules.FX_PMD -> if (param <= 0x7F) s = s.copy(pmd = param)
                    OpmRules.FX_LFO_WAVE -> if (param <= 3) s = s.copy(lfoWave = param)
                    OpmRules.FX_CSM -> if (param <= 1) s = s.copy(csm = param == 1)
                    OpmRules.FX_TIMER_A -> s = s.copy(timerA = (param shl 2) or (s.timerA and 3))
                    OpmRules.FX_NOISE -> if (ch == NOISE && param <= 0x1F) {
                        s = s.copy(noise = (s.noise and 0x80) or param)
                        lock = param
                    }
                    OpmRules.FX_PORTAMENTO -> if (ch == NOISE) portamento = param
                    OpmRules.FX_SLIDE_UP, OpmRules.FX_SLIDE_DOWN -> if (ch == NOISE) portamento = 0
                    OpmRules.FX_CUT -> if (ch == NOISE) cut = param
                }
            }
            if (ch != NOISE) return
            if (c.note == Cell.CUT) keyed = false
            else if (c.note >= 0 && !(portamento > 0 && keyed)) {
                val ins = sound.instrument(instrument)
                if (ins != null) {
                    var noise = if (ins.noise) 0x80 or ins.noiseFrequency else s.noise and 0x1F
                    if (lock >= 0) noise = (noise and 0x80) or lock
                    s = s.copy(noise = noise)
                    keyed = true
                }
            }
            if (cut in tick until speed) keyed = false
        }
    }
}
