package org.thortracker.system.opl3

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.Opl3PlayerImage
import org.thortracker.backend.Opl3Lowering
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
import org.thortracker.system.SongSetting
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * The Yamaha YMF262 (OPL3) played by Thor Tracker's own player on Nuked-OPL3 (native `"opl3-fm"`; music-only, exported
 * as VGM): nine FM channels (1-6 may play 4-op instruments, 7-9 2-op), spare chip voices for chords, up to four effect
 * columns per channel, one bank of [SLOTS] instruments with operator registers, curated controls ([Opl3Curated]) and
 * a drum pitch sweep. See docs/BACKENDS.md ("FM", "OPL3 backend") and docs/AUDIO_ENGINE.md ("OPL3").
 */
object Opl3System : SoundSystem {
    override val id = "opl3-fm"
    override val name = "OPL3 FM (YMF262)"

    const val SLOTS = Opl3PlayerImage.MAX_INSTRUMENTS
    const val MAX_CHORDS = Opl3PlayerImage.MAX_SHAPES
    const val MAX_MULTISPEED = Opl3PlayerImage.MAX_MULTISPEED

    override val channels = Opl3Rules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.VOICE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = Opl3Rules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = Opl3Rules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(ExportFormat("vgm") { Opl3Vgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is a note-column event (key off). */
    override val offEffect: String? = null

    /** One player tick per 60 Hz frame at multispeed 1. */
    override val tickHz = 60.0
    override val maxOrders = 256

    override fun ticksPerSecond(song: Song): Double = tickHz * song.opl3.multispeed

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = Opl3Sound.blank(),
    )

    override val soundSerializer: KSerializer<Opl3Sound> = Opl3Sound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = Opl3DemoSong.build()

    override fun effectsFor(channel: Int): List<EffectSpec> = Opl3Rules.EFFECTS

    override fun effectColumns(channel: Int): Int = Opl3Rules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = Opl3Rules.describe(song, effect, param)

    /** Chords and arpeggios read with the notes they sound from the row's note ([Opl3EffectText]). */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        Opl3EffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = Opl3Validator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = Opl3Validator.structuralErrors(song)
    override fun errors(song: Song) = Opl3Validator.errors(song)
    override fun warnings(song: Song) = Opl3Validator.warnings(song)

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
        for (c in 0..cell.more.size) if (cell.effectCode(c) == Opl3Rules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == Opl3Rules.FX_JUMP) out = out.withEffect(c, Opl3Rules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.let(Opl3Curated::summary) ?: "Empty: choose a preset"

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** What [instrumentName] shows for an empty slot. */
    const val EMPTY_NAME = "(empty)"

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): Opl3Instrument? = song.opl3.instrument(slot)

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) Opl3InstrumentView else null

    override val songSettings: List<SongSetting> get() = Opl3SongSettings.ALL

    private object Engine : PlaybackEngine {
        override val id = "opl3-fm"
        override val driver = "Thor Tracker's OPL3 player on Nuked-OPL3 1.8 (commit 765ec96), blip_buf 1.1.0"
        override val imageSize = Opl3PlayerImage.IMAGE_SIZE

        /** At 60 ticks per second, ~3.3 minutes of song. */
        override val seekTicks = Opl3PlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = Opl3Lowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) song.opl3.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
                ins.fourOp && channel >= Opl3Rules.FOUR_OP_CHANNELS -> "\"${ins.name}\" is 4-op; audition it on FM 1-6"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> null
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column: the
     * player's pitch unit is 1/64 semitone (1.56 cents), so every degree is realised within 0.8 cents of the request,
     * plus the F-number table's own rounding (≤ 2.6 cents from 12-TET on the chip).
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
            return if (p >= Opl3Rules.FINE_CENTRE) p - Opl3Rules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= Opl3Rules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < Opl3Rules.FINE_CENTRE) return null
            val col = current ?: (0 until Opl3Rules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, Opl3Rules.FX_FINE, Opl3Rules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == Opl3Rules.FX_FINE }
    }

    private object Help : SystemHelp {
        override val off = "Key off: the note fades with its instrument's release (its chord notes too)"
        override val offEntered = "OFF: key off (the release plays)"
        override val offInspector = "OFF: key off (release phase)"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player on a YMF262 (Nuked-OPL3): 60 ticks per second times the song's multispeed.",
            "Up to four effect columns per channel, run in column order. Locks (E, I, M, W) and chords (H, K) shape the note " +
                "started on their row only; volume, pan, slides and vibrato act from their row on.",
            "Channels FM 1-6 can play 4-op instruments; FM 7-9 play 2-op. Chords use the chip's spare voices: nine in a 2-op " +
                "song, three when all six 4-op pairs are busy.",
            "Drums are instruments with a pitch sweep: the note picks the drum's pitch.",
        )
        override val tonalChannels = "all nine FM channels"
        override val tonalChannelsShort = "FM"
        override val untonalNote = "Every channel is tonal; drums sweep from their note."
        override val sketchChannels = "FM 1-9"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = Opl3InstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = Opl3Validator.limits(song)
    }
}

/** The Song view's OPL3 rows: multispeed and the chip's two LFO depths. */
object Opl3SongSettings {
    private fun with(song: Song, f: (Opl3Sound) -> Opl3Sound): Song = f(song.opl3).let { if (it == song.opl3) song else song.copy(sound = it) }

    val ALL: List<SongSetting> = listOf(
        SongSetting("Multispeed", "Player ticks per frame", "Set multispeed",
            value = { s ->
                val m = s.opl3.multispeed
                if (m == 1) "1×   60 ticks per second" else "$m×   ${60 * m} ticks per second: speeds and effects count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + 4 * c).coerceIn(1, Opl3System.MAX_MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ),
        SongSetting("Tremolo", "Tremolo depth", "Set tremolo depth",
            value = { s -> if (s.opl3.deepTremolo) "Deep   4.8 dB" else "Light   1 dB" },
            adjust = { s, f, c -> if ((f + c) % 2 == 0) s else with(s) { it.copy(deepTremolo = !it.deepTremolo) } },
            reset = { s -> with(s) { it.copy(deepTremolo = false) } },
        ),
        SongSetting("Vibrato", "Vibrato depth", "Set vibrato depth",
            value = { s -> if (s.opl3.deepVibrato) "Deep   14 cents" else "Light   7 cents" },
            adjust = { s, f, c -> if ((f + c) % 2 == 0) s else with(s) { it.copy(deepVibrato = !it.deepVibrato) } },
            reset = { s -> with(s) { it.copy(deepVibrato = false) } },
        ),
    )
}
