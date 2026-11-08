package org.thortracker.system.scc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.SccLowering
import org.thortracker.backend.SccPlayerImage
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

/** The Konami SCC (K051649) of the MSX megaROM cartridges: channels 4 and 5 share one wave RAM. */
object SccSystem : KonamiSccSystem(SccVariant.SCC) {
    override fun demo(): Song = SccDemoSong.build()
}

/** The Konami SCC+ (K052539 of the Sound Cartridge, in SCC+ mode): five independent wave RAMs. */
object SccPlusSystem : KonamiSccSystem(SccVariant.PLUS) {
    override fun demo(): Song = SccDemoSong.buildPlus()
}

/**
 * A Konami wavetable chip at the MSX clock (3579545 Hz), played by Thor Tracker's own player on a port of MAME's k051649
 * core (native `"msx-scc"` / `"msx-scc-plus"`; music-only, exported as VGM): five channels of 32-sample signed 8-bit
 * waves with 12-bit periods and 4-bit volumes, up to four effect columns per channel, one bank of [SLOTS] instruments
 * made of envelopes (volume, arpeggio, pitch, wave) and [SccSound.WAVES] waves per song. [variant] is the chip: the SCC's
 * channels 4 and 5 share a wave RAM, the SCC+ has five. See docs/AUDIO_ENGINE.md ("SCC and SCC+").
 */
abstract class KonamiSccSystem internal constructor(val variant: SccVariant) : SoundSystem {
    override val id: String = when (variant) {
        SccVariant.SCC -> SccPlayerImage.ENGINE_ID_SCC
        SccVariant.PLUS -> SccPlayerImage.ENGINE_ID_PLUS
    }

    override val name: String = when (variant) {
        SccVariant.SCC -> "Konami SCC (K051649)"
        SccVariant.PLUS -> "Konami SCC+ (K052539)"
    }

    override val family: String = "MSX SCC"

    override val channels = SccRules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.WAVE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = SccRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = SccRules.EFFECTS
    override val views = ViewKind.entries.toSet()
    override val exports = listOf(ExportFormat("vgm") { SccVgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help(variant)

    /** OFF is a note-column event (key off). */
    override val offEffect: String? = null

    /** One player tick per 60 Hz frame at multispeed 1. */
    override val tickHz = 60.0
    override val maxOrders = SccPlayerImage.MAX_ORDERS

    override fun ticksPerSecond(song: Song): Double = tickHz * song.scc.multispeed

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = SccSound.blank(variant),
    )

    override val soundSerializer: KSerializer<SccSound> = SccSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun effectsFor(channel: Int): List<EffectSpec> = SccRules.EFFECTS

    override fun effectColumns(channel: Int): Int = SccRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = SccRules.describe(song, channel, effect, param)

    /** Arpeggio notes, what a wave change reaches (the SCC's shared RAM included) and the test register's bits. */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        SccEffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = SccValidator.cellProblem(song, channel, cell)

    /** [SccValidator]'s checks, after making sure the song's chip data is this system's variant (a project's backend id). */
    override fun structuralErrors(song: Song): List<String> = variantProblem(song)?.let { listOf(it) } ?: SccValidator.structuralErrors(song)
    override fun errors(song: Song): List<String> = variantProblem(song)?.let { listOf(it) } ?: SccValidator.errors(song)

    private fun variantProblem(song: Song): String? = (song.sound as? SccSound)?.variant?.takeIf { it != variant }?.let {
        "The song's chip data is for the ${it.label} (${it.chip}), not the ${variant.label} (${variant.chip})"
    }

    override fun warnings(song: Song) = SccValidator.warnings(song)

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
        for (c in 0..cell.more.size) if (cell.effectCode(c) == SccRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == SccRules.FX_JUMP) out = out.withEffect(c, SccRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine(this)

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.let { SccRules.summary(it, song.scc) } ?: "Empty: choose a preset"

    /** The first wave the instrument plays, its samples −128..127 as 0..1. */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0 || slot !in 1..SLOTS) return null
        val first = instrument(song, slot)?.wave?.values?.firstOrNull() ?: return null
        return song.scc.wave(first)?.samples?.map { (it + 128) / 255f }
    }

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): SccInstrument? = song.scc.instrument(slot)

    /** Points the Wave view at the selected instrument's first wave (0-based [Selection.wave]). */
    override fun selectSound(selection: Selection, song: Song): Selection {
        if (selection.bank != 0) return selection
        val first = instrument(song, selection.instrument)?.wave?.values?.firstOrNull() ?: return selection
        return if (first in 1..SccSound.WAVES) selection.copy(wave = first - 1) else selection
    }

    override fun view(kind: ViewKind): SystemView? = when (kind) {
        ViewKind.INSTRUMENT -> SccInstrumentView
        ViewKind.WAVE -> SccWaveView
        else -> null
    }

    override val songSettings: List<SongSetting> get() = SccSongSettings.ALL

    /**
     * On the SCC: which wave the RAM shared by SCC 4 and SCC 5 holds and who claimed it ([SccSharedState]); on both
     * chips the test register while it is not 0. Null on the SCC+ while the test register is 0 (nothing is shared).
     */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState? = SccSharedState.state(song, order, row)

    companion object {
        const val SLOTS = SccPlayerImage.MAX_INSTRUMENTS

        /** What [instrumentName] shows for an empty slot. */
        const val EMPTY_NAME = "(empty)"
    }

    private class Engine(private val system: KonamiSccSystem) : PlaybackEngine {
        override val id = system.id
        override val driver = "Thor Tracker's SCC player on a ${system.variant.chip} core ported from MAME (20d4667), blip_buf 1.1.0"
        override val imageSize = SccPlayerImage.IMAGE_SIZE

        /** At 60 ticks per second, ~3.3 minutes of song. */
        override val seekTicks = SccPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = SccLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) (song.sound as? SccSound)?.instrument(instrument) else null
            return when {
                song.sound !is SccSound -> "The song is for ${song.system.name}, not the SCC"
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in system.channels.indices -> "Cannot audition on channel $channel"
                note !in system.noteRange ->
                    "${system.noteName(note)} is outside ${system.noteName(system.noteRange.first)}..${system.noteName(system.noteRange.last)}, " +
                        "the notes the SCC's 12-bit period reaches"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> SccValidator.instrumentProblem(ins)?.let { "Instrument ${NumberText.hex(instrument, 2)} cannot play: $it" }
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column: the
     * player's pitch unit is 1/64 semitone, so every degree is realised within 0.8 cents of the request, plus the
     * 12-bit period's own rounding (within 4.2 cents of 12-TET up to G#5, 12 cents up to G#6, +19.2 cents at B-7).
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
            return if (p >= SccRules.FINE_CENTRE) p - SccRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= SccRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < SccRules.FINE_CENTRE) return null
            val col = current ?: (0 until SccRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, SccRules.FX_FINE, SccRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == SccRules.FX_FINE }
    }

    private class Help(private val variant: SccVariant) : SystemHelp {
        override val off = "Key off: a note whose volume envelope has a release point plays its release (a second OFF cuts it); " +
            "any other note falls silent at its waveform's next step"
        override val offEntered = "OFF: key off (the release plays if the instrument has one)"
        override val offInspector = "OFF: key off (release, else silence)"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player on a Konami ${variant.chip} (MAME's k051649 core) at the MSX clock, " +
                "3.579545 MHz: 60 ticks per second times the song's multispeed.",
            "Five channels, each a 32-sample signed 8-bit wave at a 12-bit period with 16 volume steps. Instruments are " +
                "envelopes stepped every tick: volume, arpeggio, pitch (relative pitch sweeps for drums) and the waves the " +
                "note plays (wave numbers 01-40; the Wave view draws them).",
            when (variant) {
                SccVariant.SCC -> "SCC 4 and SCC 5 share one wave RAM on the K051649: whatever it holds, both channels play. " +
                    "The channel that last keyed a note or changed its wave (Wxx, a wave envelope step) writes its wave there, " +
                    "so when SCC 4 and SCC 5 sound together with different waves both play the latest one. Give them the same " +
                    "wave, alternate phrases (OFF before the other enters), or switch the song to SCC+, which has five wave " +
                    "RAMs. The Pattern lane headers of SCC 4 and SCC 5 show the wave the shared RAM holds; the song check " +
                    "lists overlaps."
                SccVariant.PLUS -> "The SCC+ (K052539 in SCC+ mode) gives every channel its own wave RAM, so all five " +
                    "channels play independent waves (on the SCC, channels 4 and 5 share one)."
            },
            "Up to four effect columns per channel, run in column order. 0xy, Pxx and Wxx shape the note on their row (on a " +
                "row without a note, the sounding note); volume, slides, vibrato and the test register act from their row on.",
            "Txx writes the chip's test register for every channel: T20 restarts waveforms at each period write, T40 " +
                "freezes every wave RAM, T80 the SCC's shared one (the SCC+ ignores it), T00 is normal; bits 0-1 are " +
                "frequency modes measured on one chip only, and VGM players ignore them.",
            "No chords: each channel is one fixed voice, so spread chords over channels or use arpeggios. Drums are " +
                "instruments: a sine with a relative pitch sweep for kicks, noise-like waves played high for snares and hats.",
        )
        override val tonalChannels = "all five SCC channels"
        override val tonalChannelsShort = "SCC"
        override val untonalNote = "Every channel is tonal; drums sweep or buzz from their note."
        override val sketchChannels = "SCC 1-5"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = SccInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = SccValidator.limits(song)
    }
}

/** The Song view's SCC row: multispeed (both chips). */
object SccSongSettings {
    private fun with(song: Song, f: (SccSound) -> SccSound): Song = f(song.scc).let { if (it == song.scc) song else song.copy(sound = it) }

    val ALL: List<SongSetting> = listOf(
        SongSetting("Multispeed", "Player ticks per frame", "Set multispeed",
            value = { s ->
                val m = s.scc.multispeed
                if (m == 1) "1×   60 ticks per second" else "$m×   ${60 * m} ticks per second: speeds and effects count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + 4 * c).coerceIn(1, SccPlayerImage.MAX_MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ),
    )
}

/**
 * The chip's shared state where straight play reaches a song position ([SccWalk], document orders: those that play
 * before it, a parked order only on its own, rows up to and including the position's): on the SCC, the wave the RAM of
 * SCC 4 and SCC 5 holds (a "W05" tag on both lanes; "+" when the claiming note's wave envelope steps through several
 * waves) and inspector lines saying who claimed it and what that means for the other channel; on both chips, a line
 * for a test register that is not 0.
 */
object SccSharedState {
    private var cacheSong: Song? = null
    private var cacheOrder = -1
    private var cacheRow = -1
    private var cacheState: SharedState? = null

    /** The walk after row [row] of order [order] has run (the song start when [order] does not exist). */
    internal fun walk(song: Song, order: Int, row: Int): SccWalk {
        val walk = SccWalk(song.scc, song.ticksPerRow)
        if (order !in song.orders.indices) return walk
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before + order) {
            val p = song.patterns.getOrNull(song.orders[o]) ?: continue
            if (p.tracks.size != SccSound.CHANNELS || p.tracks.any { it.size != Songs.ROWS }) continue
            val length = p.length.coerceIn(1, Songs.ROWS)
            val last = if (o == order) minOf(row, length - 1) else length - 1
            for (r in 0..last) walk.row(p.tracks, o, r)
        }
        return walk
    }

    /** The wave number the SCC's shared RAM holds after row [row] of order [order] (0 = never written; SCC+: 0). */
    fun sharedWave(song: Song, order: Int, row: Int): Int = walk(song, order, row).held

    /** The lane tags and inspector lines (null on the SCC+ while the test register is 0). Cached per position. */
    fun state(song: Song, order: Int, row: Int): SharedState? {
        if (song === cacheSong && order == cacheOrder && row == cacheRow) return cacheState
        val state = compute(song, order, row)
        cacheSong = song; cacheOrder = order; cacheRow = row; cacheState = state
        return state
    }

    private fun compute(song: Song, order: Int, row: Int): SharedState? {
        val sound = song.sound as? SccSound ?: return null
        val w = walk(song, order, row)
        val tags = arrayOfNulls<String>(SccSound.CHANNELS)
        val lines = ArrayList<String>()
        if (sound.variant.sharedWave) {
            val claim = w.claim
            if (claim == null) {
                lines += "Shared wave RAM (SCC 4 + SCC 5): not claimed yet; the first note on either channel fills it"
            } else {
                val ins = sound.instrument(claim.slot)
                val stepping = w.lock[claim.channel] == 0 && ins != null && ins.wave.values.distinct().size > 1
                if (w.held != 0) {
                    val tag = "W" + NumberText.hex(w.held, 2) + if (stepping) "+" else ""
                    for (ch in SccSound.SHARED_CHANNELS) tags[ch] = tag
                }
                lines += "Shared wave RAM (SCC 4 + SCC 5) holds ${w.heldText(song)}; both channels play it"
                if (stepping && ins != null) {
                    lines += "Instrument ${NumberText.hex(claim.slot, 2)} \"${ins.name}\" steps through waves ${SccRules.waveList(ins.wave)}: " +
                        "each step re-claims the RAM for ${SccRules.CHANNEL_NAMES[claim.channel]}, so both channels follow its wave envelope"
                }
                val other = if (claim.channel == SccSound.SHARED_CHANNELS.first) SccSound.SHARED_CHANNELS.last else SccSound.SHARED_CHANNELS.first
                if (w.keyed[other]) {
                    val own = w.waveSet(other)
                    val heldSamples = sound.wave(w.held)?.samples
                    if (own.map { sound.wave(it)?.samples }.toSet() != setOf(heldSamples)) {
                        val ownText = if (own.size == 1) SccRules.waveLabel(song, own[0]) else "waves " + own.joinToString(", ") { NumberText.hex(it, 2) }
                        lines += "${SccRules.CHANNEL_NAMES[other]} is sounding too: it plays this wave, not its own $ownText"
                    }
                }
            }
        }
        if (w.test != 0) {
            lines += "Test register T${NumberText.hex(w.test, 2)}: " + SccRules.testMeanings(w.test, sound.variant).joinToString("; ")
        }
        if (!sound.variant.sharedWave && lines.isEmpty()) return null
        return SharedState(tags.toList(), lines)
    }
}
